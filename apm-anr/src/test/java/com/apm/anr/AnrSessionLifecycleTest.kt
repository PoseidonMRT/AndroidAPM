package com.apm.anr

import com.apm.core.Apm
import com.apm.core.ApmConfig
import com.apm.core.diagnostics.DiagnosticsConfig
import com.apm.model.ApmEvent
import com.apm.uploader.ApmUploader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses the actual ANR analysis and core pipeline to prove cancellation across stop/new initialization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnrSessionLifecycleTest {
    /** Test-owned uploads, observed through the actual dispatcher/outbox uploader. */
    private val uploaded = CopyOnWriteArrayList<ApmEvent>()

    /** Removes test modules and resets consent before the real SDK starts. */
    @Before
    fun setUp() {
        Apm.stop()
        Apm.grantCollectionConsent()
        clearModules()
    }

    /** Ensures the registered watchdog and native flags cannot leak into another test. */
    @After
    fun tearDown() {
        Apm.stop()
        Apm.grantCollectionConsent()
        clearModules()
    }

    /** Interrupted stack sampling used to continue reporting through global Apm into a new runtime. */
    @Test
    fun `analysis completing after stop cannot publish to new initialization`() {
        initSdk("old")
        val module = AnrModule(AnrConfig(
            enableSigquitDetection = false,
            enableTracesFileReading = false,
            checkIntervalMs = SLOW_SAMPLE_MS,
            stackSampleIntervalMs = SLOW_SAMPLE_MS,
            stackSampleCount = 3
        ))
        Apm.register(module)
        val session = AnrModule::class.java.getDeclaredField("detectionSession")
            .apply { isAccessible = true }.get(module) as AnrDetectionSession
        val analyze = AnrModule::class.java.getDeclaredMethod(
            "handleAnrDetection", AnrDetectionSession::class.java, String::class.java,
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val failure = AtomicReference<Throwable?>()
        // This independent analysis invocation is deliberately not cancelled by executor shutdown;
        // it models a JNI/IO boundary that returns only after SDK shutdown has completed.
        val worker = Thread {
            try { analyze.invoke(module, session, "sigquit", 5_000L) }
            catch (error: Throwable) { failure.set(error) }
        }.apply { start() }
        try {
            awaitStackSampling(worker)
            Apm.stop()
            initSdk("new")
            worker.interrupt()
            worker.join(TEST_WAIT_MS)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            // Stop drains actual persisted events before returning; a misplaced late ANR is observable.
            Apm.stop()
            assertTrue(uploaded.none { it.name == "anr_detected" })
        } finally {
            worker.interrupt()
            worker.join(TEST_WAIT_MS)
        }
    }

    /** Starts a real runtime with a custom collector and distinct context per initialization. */
    private fun initSdk(run: String) {
        Apm.init(RuntimeEnvironment.getApplication(), ApmConfig(
            uploader = object : ApmUploader {
                override fun upload(event: ApmEvent): Boolean { uploaded += event; return true }
            },
            defaultContext = mapOf("testRun" to run),
            enableSelfMonitoring = false,
            diagnostics = DiagnosticsConfig(enabled = false)
        ))
    }

    /** Waits for the actual ANR sampler sleep rather than racing arbitrary wall-clock delays. */
    private fun awaitStackSampling(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEST_WAIT_MS)
        while (System.nanoTime() < deadline) {
            if (worker.state == Thread.State.TIMED_WAITING &&
                worker.stackTrace.any { it.methodName == "collectStackSamples" }) return
            Thread.sleep(POLL_WAIT_MS)
        }
        throw AssertionError("ANR analysis did not reach stack sampling")
    }

    /** Clears retained registrations only after SDK stop, matching existing lifecycle-test isolation. */
    private fun clearModules() {
        val field = Apm::class.java.getDeclaredField("modules").apply { isAccessible = true }
        (field.get(Apm) as MutableList<*>).clear()
    }

    companion object {
        /** Long enough that cancellation, rather than normal completion, releases stack sampling. */
        private const val SLOW_SAMPLE_MS = 60_000L
        /** Bounded test worker waits. */
        private const val TEST_WAIT_MS = 5_000L
        /** Small poll interval used only while establishing the test interleaving. */
        private const val POLL_WAIT_MS = 10L
    }
}
