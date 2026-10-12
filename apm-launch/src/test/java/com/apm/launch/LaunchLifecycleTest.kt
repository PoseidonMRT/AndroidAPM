package com.apm.launch

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import com.apm.core.Apm
import com.apm.core.ApmConfig
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.core.BizContextProvider
import com.apm.core.StorageType
import com.apm.core.diagnostics.DiagnosticsConfig
import com.apm.model.ApmEvent
import com.apm.uploader.ApmUploader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Verifies actual scheduled first-frame cancellation, permanent run gates, and Android clock semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LaunchLifecycleTest {
    /** Module runs created manually from an actual runtime context and stopped after each test. */
    private val runningModules = ArrayList<LaunchModule>()

    /** Stops prior APM state and leaves VSync delivery under explicit test control. */
    @Before
    fun setUp() {
        Apm.stop()
        Apm.grantCollectionConsent()
        ShadowChoreographer.setPaused(true)
    }

    /** Releases module callbacks and runtime workers even when assertions fail. */
    @After
    fun tearDown() {
        runningModules.forEach(LaunchModule::onStop)
        Apm.stop()
        Apm.grantCollectionConsent()
        ShadowChoreographer.setPaused(false)
    }

    /** Queued VSync and already-captured callback objects cannot enter the next initialized runtime. */
    @Test
    fun `stop cancels first frame and late callback cannot enter a new runtime`() {
        val module = startModule()
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        module.onActivityCreated(activity, null)
        val callback = firstFrameCallback(module)
        module.onStop()
        Apm.stop()
        val nextUploader = RecordingUploader()
        initRuntime(nextUploader)

        // Simulate delivery already removed from the scheduler before stop, then advance the real queue.
        callback.doFrame(0L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_ADVANCE_MS))
        Apm.stop()

        assertFalse(firstFrameRendered(module))
        assertTrue(nextUploader.events.none { it.name == EVENT_FIRST_FRAME })
    }

    /** Restarting this module inside the same runtime must not reopen the previous callback token. */
    @Test
    fun `module restart does not reactivate an earlier first frame callback`() {
        val uploader = RecordingUploader()
        val module = startModule(uploader)
        module.onActivityCreated(Robolectric.buildActivity(Activity::class.java).get(), null)
        val callback = firstFrameCallback(module)

        module.onStop()
        module.onStart()
        callback.doFrame(0L)
        module.onStop()
        Apm.stop()

        assertFalse(firstFrameRendered(module))
        assertTrue(uploader.events.none { it.name == EVENT_FIRST_FRAME })
    }

    /** The session bridge preserves the original common context and first-frame idempotence. */
    @Test
    fun `live first frame reports once and preserves common context`() {
        val uploader = RecordingUploader()
        val module = startModule(uploader, provider = BizContextProvider { mapOf(BIZ_KEY to BIZ_VALUE) })
        module.onActivityCreated(Robolectric.buildActivity(Activity::class.java).get(), null)
        val callback = firstFrameCallback(module)

        callback.doFrame(0L)
        callback.doFrame(0L)
        module.onStop()
        Apm.stop()

        val frame = uploader.events.single { it.name == EVENT_FIRST_FRAME }
        assertEquals(DEFAULT_VALUE, frame.globalContext[DEFAULT_KEY])
        assertEquals(BIZ_VALUE, frame.globalContext[BIZ_KEY])
        assertTrue(firstFrameRendered(module))
    }

    /** A host context callback runs without the module lock, and its late result stays behind the closed token. */
    @Test
    fun `in flight first frame is rejected after same runtime module restart`() {
        val blockContext = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uploader = RecordingUploader()
        val module = startModule(uploader, provider = BizContextProvider {
            if (blockContext.get()) {
                entered.countDown()
                check(release.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            }
            emptyMap()
        })
        module.onActivityCreated(Robolectric.buildActivity(Activity::class.java).get(), null)
        val callback = firstFrameCallback(module)
        blockContext.set(true)
        val failure = AtomicReference<Throwable>()
        val callbackThread = Thread {
            try {
                callback.doFrame(0L)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            // This stop must complete while the provider is blocked; no SDK lock spans host code.
            module.onStop()
            module.onStart()
        } finally {
            release.countDown()
            callbackThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
            blockContext.set(false)
        }
        assertFalse(callbackThread.isAlive)
        assertNull(failure.get())
        module.onStop()
        Apm.stop()

        assertTrue(uploader.events.none { it.name == EVENT_FIRST_FRAME })
    }

    /** Sleep separates Android uptime from elapsedRealtime; cold start and phases must still agree exactly. */
    @Test
    fun `cold start phases share process elapsed clock including deep sleep`() {
        SystemClock.sleep(INITIAL_AWAKE_MS)
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(PRIOR_SLEEP_MS))
        assertTrue(SystemClock.elapsedRealtime() - SystemClock.uptimeMillis() >= PRIOR_SLEEP_MS)
        val uploader = RecordingUploader()
        val module = startModule(uploader, phases = true)
        val baseline = SystemClock.elapsedRealtime()
        setProcessStart(module, baseline)
        module.onAppOnCreateStart()
        // Robolectric 4.14 advanceBy calls setCurrentTimeMillis and collapses elapsed/uptime to one value.
        // Its sleep shadow advances both clocks by the duration while preserving earlier deep sleep.
        SystemClock.sleep(APP_BEFORE_PROVIDER_MS)
        module.onContentProviderCreateStart(PROVIDER_NAME)
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(PROVIDER_SLEEP_MS))
        SystemClock.sleep(PROVIDER_AWAKE_MS)
        module.onContentProviderCreateEnd(PROVIDER_NAME)
        module.onAppOnCreateEnd()
        SystemClock.sleep(BEFORE_ACTIVITY_MS)
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        module.onActivityCreated(activity, null)
        SystemClock.sleep(ACTIVITY_LIFECYCLE_MS)
        module.onActivityResumed(activity)
        SystemClock.sleep(FIRST_FRAME_MS)
        firstFrameCallback(module).doFrame(0L)
        module.onStop()
        Apm.stop()

        val cold = uploader.events.single { it.name == EVENT_COLD_START }
        val frame = uploader.events.single { it.name == EVENT_FIRST_FRAME }
        val providerDuration = PROVIDER_SLEEP_MS + PROVIDER_AWAKE_MS
        val appDuration = APP_BEFORE_PROVIDER_MS + providerDuration
        assertEquals(appDuration + BEFORE_ACTIVITY_MS, cold.fields[FIELD_DURATION_MS])
        assertEquals(appDuration + BEFORE_ACTIVITY_MS + ACTIVITY_LIFECYCLE_MS + FIRST_FRAME_MS,
            frame.fields[FIELD_DURATION_MS])
        assertEquals(appDuration, frame.fields[FIELD_APP_CREATE_MS])
        assertEquals(providerDuration, frame.fields[FIELD_PROVIDER_MS])
        assertEquals(BEFORE_ACTIVITY_MS, frame.fields[FIELD_BEFORE_ACTIVITY_MS])
        assertEquals(ACTIVITY_LIFECYCLE_MS, frame.fields[FIELD_ACTIVITY_LIFECYCLE_MS])
        assertEquals(FIRST_FRAME_MS, frame.fields[FIELD_FIRST_FRAME_MS])
    }

    /** Initializes a real SDK context then starts one independently controlled Launch run. */
    private fun startModule(
        uploader: RecordingUploader = RecordingUploader(),
        provider: BizContextProvider = BizContextProvider.EMPTY,
        phases: Boolean = false
    ): LaunchModule {
        val context = initRuntime(uploader, provider)
        return LaunchModule(LaunchConfig(enablePhaseTracking = phases)).also { module ->
            runningModules += module
            module.onInitialize(context)
            // Robolectric does not expose kernel process-start time; inject the matching elapsed baseline.
            setProcessStart(module, SystemClock.elapsedRealtime())
            module.onStart()
        }
    }

    /** Uses a unique capture module so tests do not overwrite the SDK's retained module registrations. */
    private fun initRuntime(
        uploader: RecordingUploader,
        provider: BizContextProvider = BizContextProvider.EMPTY
    ): ApmContext {
        val capture = ContextCaptureModule()
        Apm.register(capture)
        Apm.init(RuntimeEnvironment.getApplication(), ApmConfig(
            storageType = StorageType.FILE,
            uploader = uploader,
            diagnostics = DiagnosticsConfig(enabled = false),
            enableSelfMonitoring = false,
            enableAutoThrottle = false,
            enableRetry = false,
            defaultContext = mapOf(DEFAULT_KEY to DEFAULT_VALUE),
            bizContextProvider = provider
        ))
        return requireNotNull(capture.context)
    }

    /** Captures the actual scheduled callback without introducing a production testing API. */
    private fun firstFrameCallback(module: LaunchModule): Choreographer.FrameCallback {
        val sessionField = LaunchModule::class.java.getDeclaredField("currentSession").apply { isAccessible = true }
        val session = requireNotNull(sessionField.get(module))
        val callbackField = session.javaClass.getDeclaredField("frameCallback").apply { isAccessible = true }
        return requireNotNull(callbackField.get(session)) as Choreographer.FrameCallback
    }

    /** Reads whether a stopped callback erroneously advanced the module's first-frame state. */
    private fun firstFrameRendered(module: LaunchModule): Boolean {
        val field = LaunchModule::class.java.getDeclaredField("firstFrameRendered").apply { isAccessible = true }
        return field.getBoolean(module)
    }

    /** Supplies an elapsed-time process baseline where Robolectric lacks the kernel timestamp. */
    private fun setProcessStart(module: LaunchModule, startMs: Long) {
        val field = LaunchModule::class.java.getDeclaredField("processStartMs").apply { isAccessible = true }
        field.setLong(module, startMs)
    }

    /** Collects real dispatcher output while leaving network operations out of lifecycle regressions. */
    private class RecordingUploader : ApmUploader {
        /** Events handed off by the real asynchronous dispatcher. */
        val events = ConcurrentLinkedQueue<ApmEvent>()
        /** Accepts one event and records its complete context. */
        override fun upload(event: ApmEvent): Boolean {
            events.offer(event)
            return true
        }
    }

    /** Captures a public module context sharing the runtime which owns the real dispatcher. */
    private class ContextCaptureModule : ApmModule {
        /** Unique registration identity across singleton lifecycle tests. */
        override val name: String = "launch-context-${UUID.randomUUID()}"
        /** Most recently initialized runtime context. */
        var context: ApmContext? = null
        /** Captures the initialized context for the manually controlled Launch run. */
        override fun onInitialize(context: ApmContext) { this.context = context }
        /** No independent monitor resources are needed. */
        override fun onStart() = Unit
        /** No independent monitor resources need release. */
        override fun onStop() = Unit
    }

    companion object {
        /** Bounded callback/provider waits. */
        private const val AWAIT_SECONDS = 5L
        /** Advances beyond a pending VSync after stop. */
        private const val FRAME_ADVANCE_MS = 100L
        /** Initial uptime before simulating prior device suspend. */
        private const val INITIAL_AWAKE_MS = 1_000L
        /** Separates boot elapsed time from CPU uptime before process launch. */
        private const val PRIOR_SLEEP_MS = 600_000L
        /** Application initialization before provider entry. */
        private const val APP_BEFORE_PROVIDER_MS = 20L
        /** Device sleep included in the provider phase. */
        private const val PROVIDER_SLEEP_MS = 100L
        /** Awake provider execution time. */
        private const val PROVIDER_AWAKE_MS = 30L
        /** Gap between Application completion and Activity creation. */
        private const val BEFORE_ACTIVITY_MS = 100L
        /** Activity create-to-resume phase. */
        private const val ACTIVITY_LIFECYCLE_MS = 50L
        /** Resume-to-first-frame callback phase. */
        private const val FIRST_FRAME_MS = 40L
        /** Provider phase fixture identity. */
        private const val PROVIDER_NAME = "FixtureProvider"
        /** Stable first-frame event name. */
        private const val EVENT_FIRST_FRAME = "first_frame_rendered"
        /** Stable cold-start event name. */
        private const val EVENT_COLD_START = "cold_start"
        /** Total launch duration field. */
        private const val FIELD_DURATION_MS = "launchDurationMs"
        /** App initialization duration field. */
        private const val FIELD_APP_CREATE_MS = "phaseAppCreateMs"
        /** Provider duration field. */
        private const val FIELD_PROVIDER_MS = "phaseContentProviderMs"
        /** App-to-Activity gap field. */
        private const val FIELD_BEFORE_ACTIVITY_MS = "phaseBeforeActivityMs"
        /** Activity lifecycle duration field. */
        private const val FIELD_ACTIVITY_LIFECYCLE_MS = "phaseActivityLifecycleMs"
        /** First-frame phase duration field. */
        private const val FIELD_FIRST_FRAME_MS = "phaseFirstFrameMs"
        /** Static context fixture key. */
        private const val DEFAULT_KEY = "releaseChannel"
        /** Static context fixture value. */
        private const val DEFAULT_VALUE = "test"
        /** Business context fixture key. */
        private const val BIZ_KEY = "checkoutStage"
        /** Business context fixture value. */
        private const val BIZ_VALUE = "payment"
    }
}
