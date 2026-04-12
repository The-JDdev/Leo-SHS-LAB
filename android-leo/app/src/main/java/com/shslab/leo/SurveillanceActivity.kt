package com.shslab.leo

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.shslab.leo.browser.LeoBrowserEngine
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════════════════
 *  LEO SURVEILLANCE SCREEN — APEX v5
 *  SHS LAB — LIVE AGENT MONITOR
 *
 *  JD can watch Leo's internal browser and terminal
 *  executing tasks in real-time.
 *
 *  Two panels:
 *   - Top: Chromium WebView (browser engine live view)
 *   - Bottom: Terminal/action log feed
 * ══════════════════════════════════════════════════════
 */
class SurveillanceActivity : AppCompatActivity() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var tvTerminalLog: TextView
    private lateinit var scrollTerminal: ScrollView
    private lateinit var tvCurrentUrl: TextView
    private lateinit var switchPanel: Switch

    private val terminalLines = mutableListOf<String>()
    private val MAX_LINES = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_surveillance)
        supportActionBar?.title = "Leo Surveillance"

        bindViews()
        attachBrowserView()
        hookIntoLogger()
    }

    private fun bindViews() {
        webViewContainer = findViewById(R.id.webViewContainer)
        tvTerminalLog    = findViewById(R.id.tvTerminalLog)
        scrollTerminal   = findViewById(R.id.scrollTerminal)
        tvCurrentUrl     = findViewById(R.id.tvCurrentUrl)
        switchPanel      = findViewById(R.id.switchPanel)

        switchPanel.setOnCheckedChangeListener { _, showBrowser ->
            webViewContainer.visibility = if (showBrowser) android.view.View.VISIBLE else android.view.View.GONE
            scrollTerminal.visibility   = if (!showBrowser) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun attachBrowserView() {
        val browserEngine = LeoBrowserEngine.getInstance(this)
        val webView = browserEngine.getSurveillanceWebView()

        val parent = webView.parent as? ViewGroup
        parent?.removeView(webView)

        webViewContainer.addView(webView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        tvCurrentUrl.text = webView.url ?: "about:blank"
    }

    private fun hookIntoLogger() {
        Logger.logCallback = { line ->
            runOnUiThread {
                addTerminalLine(line)
                val url = (webViewContainer.getChildAt(0) as? WebView)?.url
                if (url != null) tvCurrentUrl.text = url
            }
        }

        // Show existing log
        addTerminalLine("=== Leo Surveillance Active ===")
        addTerminalLine("Browser engine: ${LeoBrowserEngine.getInstance(this).getCurrentUrl()}")
        addTerminalLine("Waiting for Leo to execute actions...")
    }

    private fun addTerminalLine(line: String) {
        terminalLines.add(line)
        if (terminalLines.size > MAX_LINES) {
            terminalLines.removeAt(0)
        }
        tvTerminalLog.text = terminalLines.joinToString("\n")
        scrollTerminal.post { scrollTerminal.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        val webView = webViewContainer.getChildAt(0) as? WebView
        if (webView != null) {
            webViewContainer.removeView(webView)
        }
        Logger.logCallback = null
    }
}
