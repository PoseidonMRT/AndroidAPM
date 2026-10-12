package com.apm.webview

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.os.Build
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.apm.core.Apm
import java.util.WeakHashMap

/**
 * Delegating WebViewClient that observes supported navigation callbacks while
 * preserving host behavior. Hosts receive the same arguments and return paths.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class MonitoringWebViewClient(
    /** Monitoring callback target. */
    private val module: WebviewModule,
    /** Host-owned client whose behavior must be preserved. */
    private val delegate: WebViewClient
) : WebViewClient() {
    /** Coordinates per-view snapshot replacement with background resource admission. */
    private val snapshotLock = Any()

    /** UI-captured URLs keyed weakly by each WebView, including clients shared across multiple instances. */
    private val pageUrls = WeakHashMap<WebView, String>()

    /** Records navigation start before forwarding it. */
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        // WebView getters cannot be used from shouldInterceptRequest's background thread.
        monitorSafely {
            synchronized(snapshotLock) {
                pageUrls.remove(view)?.let(module::onNavigationReplaced)
                url?.let {
                    module.onPageStarted(it)
                    pageUrls[view] = it
                }
            }
        }
        delegate.onPageStarted(view, url, favicon)
    }

    /** Records navigation completion before forwarding it. */
    override fun onPageFinished(view: WebView, url: String?) {
        synchronized(snapshotLock) {
            if (pageUrls[view] == url) {
                // Close admission before page reporting invokes any blocking host business-context callback.
                pageUrls.remove(view)
            }
        }
        monitorSafely { url?.let(module::onPageFinished) }
        delegate.onPageFinished(view, url)
    }

    /** Forwards URL override decisions for request-based navigation. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        delegate.shouldOverrideUrlLoading(view, request)

    /** Forwards legacy URL override decisions. */
    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        delegate.shouldOverrideUrlLoading(view, url)

    /** Records resource start and preserves the host interception response. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        monitorSafely {
            synchronized(snapshotLock) {
                val page = pageUrls[view] ?: return@synchronized
                // Start admission is short and does not report events; finish closes this same gate first.
                module.getResourceWaterfall()?.onResourceStart(page, request)
            }
        }
        return delegate.shouldInterceptRequest(view, request)
    }

    /** Forwards legacy resource interception. */
    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        delegate.shouldInterceptRequest(view, url)

    /** Forwards individual resource load notifications. */
    override fun onLoadResource(view: WebView, url: String?) = delegate.onLoadResource(view, url)

    /** Forwards visible-page commit notifications. */
    override fun onPageCommitVisible(view: WebView, url: String?) {
        monitorSafely { url?.let(module::onPageVisible) }
        delegate.onPageCommitVisible(view, url)
    }

    /** Forwards modern resource failures. */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
        delegate.onReceivedError(view, request, error)

    /** Forwards legacy resource failures. */
    @Suppress("DEPRECATION")
    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) =
        delegate.onReceivedError(view, errorCode, description, failingUrl)

    /** Forwards HTTP failures. */
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) =
        delegate.onReceivedHttpError(view, request, errorResponse)

    /** Preserves the host's renderer-exit recovery and its handled return value. */
    @TargetApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        delegate.onRenderProcessGone(view, detail)

    /** Preserves the host's Safe Browsing policy and the original response callback. */
    @TargetApi(Build.VERSION_CODES.O_MR1)
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse
    ) = delegate.onSafeBrowsingHit(view, request, threatType, callback)

    /** Forwards form resubmission decisions. */
    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) =
        delegate.onFormResubmission(view, dontResend, resend)

    /** Forwards visited-history updates. */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
        delegate.doUpdateVisitedHistory(view, url, isReload)

    /** Forwards SSL errors to the host policy. */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) =
        delegate.onReceivedSslError(view, handler, error)

    /** Forwards client-certificate requests. */
    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) =
        delegate.onReceivedClientCertRequest(view, request)

    /** Forwards HTTP authentication requests. */
    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) =
        delegate.onReceivedHttpAuthRequest(view, handler, host, realm)

    /** Forwards key override decisions. */
    override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean =
        delegate.shouldOverrideKeyEvent(view, event)

    /** Forwards unhandled key events. */
    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) = delegate.onUnhandledKeyEvent(view, event)

    /** Forwards scale changes. */
    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) =
        delegate.onScaleChanged(view, oldScale, newScale)

    /** Forwards login requests. */
    override fun onReceivedLoginRequest(view: WebView, realm: String?, account: String?, args: String?) =
        delegate.onReceivedLoginRequest(view, realm, account, args)

    /** Forwards legacy redirect handling. */
    @Suppress("DEPRECATION")
    override fun onTooManyRedirects(view: WebView, cancelMsg: Message, continueMsg: Message) =
        delegate.onTooManyRedirects(view, cancelMsg, continueMsg)

    /** Isolates recoverable monitoring failures so the host delegate still receives its callback. */
    private inline fun monitorSafely(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            // A failed observation cannot change host navigation or interception behavior.
            Apm.recordInternalError(ERROR_CALLBACK, error)
        }
    }

    companion object {
        /** Payload-free tag for an observation that failed before host delegation. */
        private const val ERROR_CALLBACK = "webview_client_callback"
    }
}
