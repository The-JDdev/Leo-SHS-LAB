package com.shslab.leo.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shslab.leo.core.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * ══════════════════════════════════════════════════════
 *  LEO BROWSER ENGINE — INBUILT CHROMIUM WEBVIEW
 *  SHS LAB v5 — APEX ARCHITECTURE
 *
 *  Hidden browser for web research, scraping, downloads.
 *  Runs silently in background — JD never sees it unless
 *  surveillance screen is opened.
 *
 *  Capabilities:
 *  - Navigate to any URL
 *  - Click elements (JS querySelector)
 *  - Type into form fields
 *  - Extract full page text
 *  - Execute arbitrary JavaScript
 *  - Download files via JS fetch or curl fallback
 * ══════════════════════════════════════════════════════
 */
class LeoBrowserEngine private constructor(context: Context) {

    val webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isPageLoading = AtomicBoolean(false)

    private var jsResultCallback: ((String) -> Unit)? = null
    private var pageLoadCallback: ((Boolean) -> Unit)? = null

    private val appContext = context.applicationContext

    companion object {
        @Volatile private var INSTANCE: LeoBrowserEngine? = null

        fun getInstance(context: Context): LeoBrowserEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LeoBrowserEngine(context).also { INSTANCE = it }
            }
        }
    }

    init {
        webView = createWebView()
    }

    private fun createWebView(): WebView {
        return WebView(appContext).apply {
            settings.apply {
                javaScriptEnabled          = true
                domStorageEnabled          = true
                loadWithOverviewMode       = true
                useWideViewPort            = true
                allowFileAccess            = true
                allowContentAccess         = true
                mixedContentMode           = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString            = "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                setSupportZoom(true)
                builtInZoomControls        = false
                displayZoomControls        = false
                cacheMode                  = WebSettings.LOAD_NO_CACHE
            }

            CookieManager.getInstance().setAcceptCookie(true)

            addJavascriptInterface(LeoJsBridge(), "LeoNative")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    isPageLoading.set(true)
                    Logger.action("[BROWSER] Loading: $url")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    isPageLoading.set(false)
                    Logger.action("[BROWSER] Loaded: $url")
                    pageLoadCallback?.invoke(true)
                    pageLoadCallback = null
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) {
                        isPageLoading.set(false)
                        Logger.warn("[BROWSER] Error: ${error.description}")
                        pageLoadCallback?.invoke(false)
                        pageLoadCallback = null
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    return true
                }
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  PUBLIC API — Called from ActionExecutor
    // ══════════════════════════════════════════════════

    /** Navigate to URL and wait for page to load */
    suspend fun navigate(url: String): String {
        val loadUrl = if (url.startsWith("http")) url else "https://$url"
        return withTimeoutOrNull(20000L) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    pageLoadCallback = { success ->
                        if (cont.isActive) cont.resume(if (success) "navigated:$loadUrl" else "nav_error:$loadUrl")
                    }
                    webView.loadUrl(loadUrl)
                }
            }
        } ?: "nav_timeout:$loadUrl"
    }

    /** Click an element by CSS selector or link text */
    suspend fun click(selector: String): String {
        val js = """
            (function() {
                var el = document.querySelector('${selector.replace("'", "\\'")}');
                if (!el) {
                    var links = document.querySelectorAll('a, button, [role=button]');
                    for (var i=0; i<links.length; i++) {
                        if (links[i].innerText && links[i].innerText.trim().toLowerCase().includes('${selector.lowercase().replace("'", "\\'")}')) {
                            links[i].click();
                            return 'clicked_text:${selector}';
                        }
                    }
                    return 'element_not_found:${selector}';
                }
                el.click();
                return 'clicked_selector:${selector}';
            })();
        """.trimIndent()
        return execJS(js)
    }

    /** Type text into a form field */
    suspend fun type(selector: String, text: String): String {
        val escaped = text.replace("'", "\\'")
        val js = """
            (function() {
                var el = document.querySelector('${selector.replace("'", "\\'")}');
                if (!el) {
                    el = document.querySelector('input, textarea, [contenteditable]');
                }
                if (!el) return 'input_not_found';
                el.focus();
                el.value = '$escaped';
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return 'typed:${text.take(30)}';
            })();
        """.trimIndent()
        return execJS(js)
    }

    /** Extract all visible text from the current page */
    suspend fun readContent(): String {
        val js = """
            (function() {
                var title = document.title;
                var url = window.location.href;
                var body = document.body ? document.body.innerText : 'no body';
                return 'URL: ' + url + '\nTITLE: ' + title + '\n\nCONTENT:\n' + body.substring(0, 4000);
            })();
        """.trimIndent()
        return execJS(js)
    }

    /** Execute arbitrary JavaScript */
    suspend fun execJS(script: String): String {
        return withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    webView.evaluateJavascript(script) { result ->
                        val cleaned = result?.removeSurrounding("\"")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "null"
                        if (cont.isActive) cont.resume(cleaned)
                    }
                }
            }
        } ?: "js_timeout"
    }

    /** Get current URL */
    fun getCurrentUrl(): String {
        var url = ""
        mainHandler.post { url = webView.url ?: "about:blank" }
        Thread.sleep(50)
        return url
    }

    /** Get current page title */
    fun getTitle(): String {
        var title = ""
        mainHandler.post { title = webView.title ?: "" }
        Thread.sleep(50)
        return title
    }

    /** Broadcast log to surveillance screen */
    fun getSurveillanceWebView(): WebView = webView

    // ══════════════════════════════════════════════════
    //  JS BRIDGE — for future JS→Kotlin callbacks
    // ══════════════════════════════════════════════════

    inner class LeoJsBridge {
        @JavascriptInterface
        fun onResult(result: String) {
            jsResultCallback?.invoke(result)
            jsResultCallback = null
        }

        @JavascriptInterface
        fun log(message: String) {
            Logger.action("[BROWSER-JS] $message")
        }
    }
}
