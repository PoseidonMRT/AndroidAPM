package com.apm.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exercises bounded page timing and the real navigation/resource completion gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebviewPageLifecycleTest {
    /** Modules whose callbacks and scheduler workers must be released after each assertion. */
    private val modules = ArrayList<WebviewModule>()

    /** Starts with explicit collection consent and no previous dispatcher. */
    @Before
    fun setUp() {
        Apm.stop()
        Apm.grantCollectionConsent()
    }

    /** Releases workers and singleton state even when a concurrency assertion fails. */
    @After
    fun tearDown() {
        modules.forEach(WebviewModule::onStop)
        Apm.stop()
        Apm.grantCollectionConsent()
    }

    /** Both the page table and delayed task closures remain bounded across abandoned navigations. */
    @Test
    fun `unfinished pages and scheduled tasks have a fixed capacity`() {
        val module = startModule()
        val scheduler = scheduler(module)
        repeat(NAVIGATION_COUNT) { page -> module.onPageStarted("$PAGE_A/$page") }

        assertEquals(PAGE_CAPACITY, pages(module).size)
        assertEquals(PAGE_CAPACITY, scheduler.queue.size)
        assertFalse(pages(module).containsKey("$PAGE_A/0"))
        assertTrue(pages(module).containsKey("$PAGE_A/${NAVIGATION_COUNT - 1}"))
        module.onStop()
        assertTrue(pages(module).isEmpty())
        assertTrue(scheduler.queue.isEmpty())
    }

    /** Visible but unfinished pages stay bounded even though their white-screen alarms are canceled. */
    @Test
    fun `visible pages retain bounded load timing without retaining canceled tasks`() {
        val module = startModule()
        val scheduler = scheduler(module)
        repeat(NAVIGATION_COUNT) { page ->
            val url = "$PAGE_A/$page"
            module.onPageStarted(url)
            module.onPageVisible(url)
        }

        assertEquals(PAGE_CAPACITY, pages(module).size)
        assertTrue(scheduler.queue.isEmpty())
    }

    /** A genuine timeout removes its timing token and emits through the captured runtime. */
    @Test
    fun `visibility timeout releases page timing and reports once`() {
        val uploader = RecordingUploader()
        val module = startModule(context = initRuntime(uploader))
        val scheduler = retainTasks(module)
        module.onPageStarted(PAGE_A)

        scheduler.commands.single().run()
        scheduler.commands.single().run()
        assertTrue(pages(module).isEmpty())
        Apm.stop()

        assertEquals(1, uploader.events.count { it.name == EVENT_WHITE_SCREEN })
    }

    /** Delivering a canceled task cannot remove a newer start with the same URL. */
    @Test
    fun `replaced navigation ignores the older timeout generation`() {
        val module = startModule()
        val scheduler = retainTasks(module)
        module.onPageStarted(PAGE_A)
        val oldPage = pages(module)[PAGE_A]
        module.onPageStarted(PAGE_A)
        val newPage = pages(module)[PAGE_A]

        scheduler.commands.first().run()
        assertFalse(oldPage === newPage)
        assertSame(newPage, pages(module)[PAGE_A])
        scheduler.commands.last().run()
        assertTrue(pages(module).isEmpty())
    }

    /** A canceled visible-page alarm must not discard the still-needed slow-load start. */
    @Test
    fun `visible page ignores an already captured timeout until finish`() {
        val module = startModule()
        val scheduler = retainTasks(module)
        module.onPageStarted(PAGE_A)
        val page = pages(module)[PAGE_A]
        module.onPageVisible(PAGE_A)

        scheduler.commands.single().run()
        assertSame(page, pages(module)[PAGE_A])
        module.onPageFinished(PAGE_A)
        assertTrue(pages(module).isEmpty())
    }

    /** Replacing a navigation frees its timer and resource budget before accepting the new page. */
    @Test
    fun `navigation replacement releases the previous page and resources`() {
        val module = startModule(resourceLimit = 1)
        val webView = WebView(RuntimeEnvironment.getApplication())
        val wrapped = module.wrapWebViewClient()
        wrapped.onPageStarted(webView, PAGE_A, null)
        wrapped.shouldInterceptRequest(webView, TestResourceRequest(RESOURCE_A))
        wrapped.onPageStarted(webView, PAGE_B, null)
        wrapped.shouldInterceptRequest(webView, TestResourceRequest(RESOURCE_B))

        assertEquals(setOf(PAGE_B), pages(module).keys)
        assertEquals(1, scheduler(module).queue.size)
        assertEquals(listOf(RESOURCE_B), resources(module)[PAGE_B]?.map { it.url })
        assertFalse(resources(module).containsKey(PAGE_A))
    }

    /** One wrapped client can serve two WebViews without attributing either page's resources to the other. */
    @Test
    fun `shared client keeps independent weak page snapshots per webview`() {
        val module = startModule()
        val viewA = WebView(RuntimeEnvironment.getApplication())
        val viewB = WebView(RuntimeEnvironment.getApplication())
        val wrapped = module.wrapWebViewClient()
        viewA.webViewClient = wrapped
        viewB.webViewClient = wrapped
        wrapped.onPageStarted(viewA, PAGE_A, null)
        wrapped.onPageStarted(viewB, PAGE_B, null)

        runWorker { wrapped.shouldInterceptRequest(viewA, TestResourceRequest(RESOURCE_A)) }
        runWorker { wrapped.shouldInterceptRequest(viewB, TestResourceRequest(RESOURCE_B)) }
        assertEquals(listOf(RESOURCE_A), resources(module)[PAGE_A]?.map { it.url })
        assertEquals(listOf(RESOURCE_B), resources(module)[PAGE_B]?.map { it.url })

        wrapped.onPageFinished(viewA, PAGE_A)
        runWorker { wrapped.shouldInterceptRequest(viewA, TestResourceRequest(RESOURCE_A)) }
        runWorker { wrapped.shouldInterceptRequest(viewB, TestResourceRequest(RESOURCE_B)) }
        assertEquals(setOf(PAGE_B), resources(module).keys)
        assertEquals(2, resources(module)[PAGE_B]?.size)
        wrapped.onPageFinished(viewB, PAGE_B)
        assertTrue(resources(module).isEmpty())
    }

    /** Closing the snapshot precedes slow host context capture, so concurrent interception cannot revive a page. */
    @Test
    fun `interception cannot revive a page while completion context is blocked`() {
        val blockContext = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uploader = RecordingUploader()
        val context = initRuntime(uploader, BizContextProvider {
            if (blockContext.get()) {
                entered.countDown()
                check(release.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            }
            emptyMap()
        })
        val module = startModule(context = context)
        val view = WebView(RuntimeEnvironment.getApplication())
        val wrapped = module.wrapWebViewClient()
        wrapped.onPageStarted(view, PAGE_A, null)
        wrapped.shouldInterceptRequest(view, TestResourceRequest(RESOURCE_A))
        blockContext.set(true)
        val failure = AtomicReference<Throwable>()
        val worker = launchWorker(failure) { wrapped.onPageFinished(view, PAGE_A) }
        try {
            assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            runWorker { wrapped.shouldInterceptRequest(view, TestResourceRequest(RESOURCE_B)) }
            assertTrue(resources(module).isEmpty())
        } finally {
            release.countDown()
            joinWorker(worker, failure)
            blockContext.set(false)
        }
        Apm.stop()
        assertEquals(1, uploader.events.count { it.name == EVENT_RESOURCE_WATERFALL })
    }

    /** A resource accepted before finish's gate closes is removed by that completion, even if its getter pauses. */
    @Test
    fun `resource admission and finish serialize without leaving a late record`() {
        val module = startModule()
        val view = WebView(RuntimeEnvironment.getApplication())
        val wrapped = module.wrapWebViewClient()
        wrapped.onPageStarted(view, PAGE_A, null)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request = object : WebResourceRequest by TestResourceRequest(RESOURCE_A) {
            /** Pauses the accepted start while it owns the shared snapshot gate. */
            override fun getUrl(): Uri {
                entered.countDown()
                check(release.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                return Uri.parse(RESOURCE_A)
            }
        }
        val admissionFailure = AtomicReference<Throwable>()
        val admission = launchWorker(admissionFailure) { wrapped.shouldInterceptRequest(view, request) }
        val finishFailure = AtomicReference<Throwable>()
        var finish: Thread? = null
        try {
            assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            finish = launchWorker(finishFailure) { wrapped.onPageFinished(view, PAGE_A) }
        } finally {
            release.countDown()
            joinWorker(admission, admissionFailure)
            finish?.let { joinWorker(it, finishFailure) }
        }
        assertTrue(resources(module).isEmpty())
    }

    /** A timeout blocked in business context capture cannot re-enter a restarted module run. */
    @Test
    fun `in flight timeout stays behind the permanent module session gate`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockContext = AtomicBoolean()
        val uploader = RecordingUploader()
        val module = startModule(context = initRuntime(uploader, BizContextProvider {
            if (blockContext.get()) {
                entered.countDown()
                check(release.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            }
            emptyMap()
        }))
        val scheduler = retainTasks(module)
        module.onPageStarted(PAGE_A)
        blockContext.set(true)
        val failure = AtomicReference<Throwable>()
        val timeout = launchWorker(failure) { scheduler.commands.single().run() }
        try {
            assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            module.onStop()
            module.onStart()
            module.onPageStarted(PAGE_A)
        } finally {
            release.countDown()
            joinWorker(timeout, failure)
            blockContext.set(false)
        }
        assertEquals(setOf(PAGE_A), pages(module).keys)
        Apm.stop()
        assertTrue(uploader.events.none { it.name == EVENT_WHITE_SCREEN })
    }

    /** Starts one manually controlled module with timers kept well beyond test execution. */
    private fun startModule(context: ApmContext? = null, resourceLimit: Int = RESOURCE_CAPACITY): WebviewModule {
        return WebviewModule(WebviewConfig(whiteScreenThresholdMs = LONG_TIMEOUT_MS,
            pageLoadThresholdMs = Long.MAX_VALUE, maxTrackedResources = resourceLimit)).also {
            modules += it
            context?.let(it::onInitialize)
            it.onStart()
        }
    }

    /** Reads retained page tokens without adding a public testing API. */
    @Suppress("UNCHECKED_CAST")
    private fun pages(module: WebviewModule): Map<String, Any> {
        val field = WebviewModule::class.java.getDeclaredField("pageLoadStartMap").apply { isAccessible = true }
        return field.get(module) as Map<String, Any>
    }

    /** Reads retained page resources after thread joins or while the reporting callback has already removed them. */
    @Suppress("UNCHECKED_CAST")
    private fun resources(module: WebviewModule): Map<String, List<ResourceRecord>> {
        val waterfall = requireNotNull(module.getResourceWaterfall())
        val field = ResourceWaterfall::class.java.getDeclaredField("pageResources").apply { isAccessible = true }
        return field.get(waterfall) as Map<String, List<ResourceRecord>>
    }

    /** Retrieves the actual scheduler to assert that cancellation releases delayed closures. */
    private fun scheduler(module: WebviewModule): ScheduledThreadPoolExecutor {
        val field = WebviewModule::class.java.getDeclaredField("visibilityScheduler").apply { isAccessible = true }
        return field.get(module) as ScheduledThreadPoolExecutor
    }

    /** Retains scheduled commands so a canceled or already-dequeued task can be delivered deterministically. */
    private fun retainTasks(module: WebviewModule): RetainingScheduler {
        val field = WebviewModule::class.java.getDeclaredField("visibilityScheduler").apply { isAccessible = true }
        (field.get(module) as ScheduledThreadPoolExecutor).shutdownNow()
        return RetainingScheduler().also { field.set(module, it) }
    }

    /** Starts one daemon callback worker and preserves any exception for its owning test thread. */
    private fun launchWorker(failure: AtomicReference<Throwable>, callback: () -> Unit): Thread {
        return Thread {
            try { callback() } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true; start() }
    }

    /** Joins a callback worker with a bounded timeout and rethrows failures through assertions. */
    private fun joinWorker(worker: Thread, failure: AtomicReference<Throwable>) {
        worker.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
        assertFalse(worker.isAlive)
        assertNull(failure.get())
    }

    /** Executes resource interception on a genuine background thread. */
    private fun runWorker(callback: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        joinWorker(launchWorker(failure, callback), failure)
    }

    /** Initializes the real dispatcher so blocking business callbacks exercise production handoff code. */
    private fun initRuntime(uploader: RecordingUploader, provider: BizContextProvider = BizContextProvider.EMPTY): ApmContext {
        val capture = ContextCaptureModule()
        Apm.register(capture)
        Apm.init(RuntimeEnvironment.getApplication(), ApmConfig(storageType = StorageType.FILE,
            uploader = uploader, diagnostics = DiagnosticsConfig(enabled = false),
            enableSelfMonitoring = false, enableAutoThrottle = false, enableRetry = false,
            bizContextProvider = provider))
        return requireNotNull(capture.context)
    }

    /** Captures runnable generations while keeping automatic timeout execution disabled. */
    private class RetainingScheduler : ScheduledThreadPoolExecutor(1, { command ->
        Thread(command).apply { isDaemon = true }
    }) {
        /** Task closures retained only by the test to simulate already-dequeued cancellation. */
        val commands = ArrayList<Runnable>()
        /** Records the scheduled task and delegates to a real cancellable future far in the future. */
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            commands += command
            return super.schedule(command, LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Collects accepted dispatcher output without performing network requests. */
    private class RecordingUploader : ApmUploader {
        /** Events accepted by the real asynchronous dispatcher. */
        val events = ConcurrentLinkedQueue<ApmEvent>()
        /** Records one event and acknowledges delivery. */
        override fun upload(event: ApmEvent): Boolean { events.offer(event); return true }
    }

    /** Captures a genuine context for a manually controlled monitoring module. */
    private class ContextCaptureModule : ApmModule {
        /** Unique singleton registration identity. */
        override val name = "webview-context-${UUID.randomUUID()}"
        /** Runtime context supplied by APM initialization. */
        var context: ApmContext? = null
        /** Saves the runtime context. */
        override fun onInitialize(context: ApmContext) { this.context = context }
        /** The fixture has no monitoring worker. */
        override fun onStart() = Unit
        /** The fixture has no worker to release. */
        override fun onStop() = Unit
    }

    companion object {
        /** Fixed production page capacity verified under abandoned navigation pressure. */
        private const val PAGE_CAPACITY = 128
        /** Enough distinct pages to expose unbounded tables and canceled scheduler closures. */
        private const val NAVIGATION_COUNT = 1_000
        /** Leaves room for resources from both shared-client test pages. */
        private const val RESOURCE_CAPACITY = 8
        /** Keeps automatic timers outside deterministic lifecycle checks. */
        private const val LONG_TIMEOUT_MS = 60_000L
        /** Bounded latch and worker completion waits. */
        private const val AWAIT_SECONDS = 5L
        /** First navigation fixture. */
        private const val PAGE_A = "https://example.test/a"
        /** Independent navigation fixture. */
        private const val PAGE_B = "https://example.test/b"
        /** Resource owned by the first page. */
        private const val RESOURCE_A = "https://example.test/a.js"
        /** Resource owned by the second page. */
        private const val RESOURCE_B = "https://example.test/b.js"
        /** Stable suspected-white-screen event name. */
        private const val EVENT_WHITE_SCREEN = "white_screen"
        /** Stable resource-summary event name. */
        private const val EVENT_RESOURCE_WATERFALL = "resource_waterfall"
    }
}
