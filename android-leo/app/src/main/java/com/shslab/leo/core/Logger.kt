package com.shslab.leo.core

import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ══════════════════════════════════════════
 *  LEO TERMINAL LOGGER — SHS LAB
 *
 *  Thread-safe logger that appends timestamped
 *  events to the terminal TextView.
 *  Batches updates to protect 2GB RAM budget.
 * ══════════════════════════════════════════
 */
object Logger {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timestampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Weak references prevent memory leaks
    private var terminalView: TextView? = null
    private var scrollView: ScrollView? = null

    // Bounded queue: max 500 lines to prevent OOM
    private val logBuffer = ConcurrentLinkedQueue<String>()
    private const val MAX_LINES = 500

    // Pending update debounce flag
    @Volatile private var updatePending = false

    fun attach(textView: TextView, scroll: ScrollView) {
        terminalView = textView
        scrollView   = scroll
    }

    fun detach() {
        terminalView = null
        scrollView   = null
    }

    // ── Public log methods ──

    fun system(msg: String)  = log("[SYS]", msg, "#00E5FF")   // Cyan
    fun leo(msg: String)     = log("[Leo]", msg, "#E91E8C")    // Magenta
    fun action(msg: String)  = log("[ACT]", msg, "#FFD600")    // Yellow
    fun error(msg: String)   = log("[ERR]", msg, "#FF1744")    // Red
    fun git(msg: String)     = log("[GIT]", msg, "#6B35A5")    // Purple
    fun file(msg: String)    = log("[FS]",  msg, "#69F0AE")    // Green
    fun net(msg: String)     = log("[NET]", msg, "#40C4FF")    // Light blue
    fun warn(msg: String)    = log("[WRN]", msg, "#FFAB40")    // Orange
    fun raw(msg: String)     = log("",      msg, "#80CBC4")    // Teal

    private fun log(tag: String, message: String, colorHex: String) {
        val ts   = timestampFmt.format(Date())
        val line = if (tag.isNotEmpty()) "$ts $tag $message" else "$ts $message"

        // Trim buffer if over limit (memory guard)
        while (logBuffer.size >= MAX_LINES) logBuffer.poll()
        logBuffer.offer(line)

        // Also print to Logcat for debugging
        android.util.Log.d("LEO_TERMINAL", line)

        scheduleUiUpdate()
    }

    private fun scheduleUiUpdate() {
        if (updatePending) return
        updatePending = true
        mainHandler.post {
            flushToTerminal()
            updatePending = false
        }
    }

    private fun flushToTerminal() {
        val tv = terminalView ?: return
        val sv = scrollView   ?: return

        // Build display text from buffer
        val sb = StringBuilder()
        logBuffer.forEach { sb.appendLine(it) }
        tv.text = sb.toString()

        // Auto-scroll to bottom
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** Clear the terminal and memory */
    fun clear() {
        logBuffer.clear()
        mainHandler.post { terminalView?.text = "" }
    }

    /** Dump log buffer to string (for crash reports) */
    fun dump(): String = logBuffer.joinToString("\n")
}
