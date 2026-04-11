package com.shslab.leo.core

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ══════════════════════════════════════════
 *  LEO LOGGER — SHS LAB (v2 — Callback Edition)
 *
 *  Thread-safe logger.
 *  Routes all events through a registered callback
 *  so MainActivity can pipe them into the Chat UI's
 *  "Thinking" accordion without touching Views directly.
 *
 *  Also prints everything to Logcat for debugging.
 * ══════════════════════════════════════════
 */
object Logger {

    private val mainHandler  = Handler(Looper.getMainLooper())
    private val timestampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Set by MainActivity before each network call to route logs into the thinking block. */
    @Volatile var logCallback: ((String) -> Unit)? = null

    /** Set this to receive ONLY conversation-visible (Leo-says) messages. */
    @Volatile var chatCallback: ((String) -> Unit)? = null

    // In-memory buffer — for crash dumps and Logcat
    private val buffer = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private const val MAX_BUFFER = 500

    // ── Public log methods ──────────────────────────

    /** System events — [SYS] cyan — goes to thinking block */
    fun system(msg: String)  = log("[SYS]", msg, isThinking = true)

    /** Leo's voice — [Leo] — goes to BOTH chat and thinking */
    fun leo(msg: String)     = log("[Leo]", msg, isThinking = false)

    /** Action events — [ACT] yellow — thinking block only */
    fun action(msg: String)  = log("[ACT]", msg, isThinking = true)

    /** Errors — [ERR] red — thinking block */
    fun error(msg: String)   = log("[ERR]", msg, isThinking = true)

    /** Git events — [GIT] purple — thinking block */
    fun git(msg: String)     = log("[GIT]", msg, isThinking = true)

    /** File events — [FS] green — thinking block */
    fun file(msg: String)    = log("[FS]",  msg, isThinking = true)

    /** Network events — [NET] light-blue — thinking block */
    fun net(msg: String)     = log("[NET]", msg, isThinking = true)

    /** Warnings — [WRN] orange — thinking block */
    fun warn(msg: String)    = log("[WRN]", msg, isThinking = true)

    /** Raw / separator lines — thinking block */
    fun raw(msg: String)     = log("",      msg, isThinking = true)

    // ──────────────────────────────────────────────────

    private fun log(tag: String, message: String, isThinking: Boolean) {
        val ts   = timestampFmt.format(Date())
        val line = if (tag.isNotEmpty()) "$ts $tag $message" else "$ts    $message"

        // Trim buffer
        while (buffer.size >= MAX_BUFFER) buffer.poll()
        buffer.offer(line)

        // Always log to Logcat
        android.util.Log.d("LEO", line)

        // Route to UI callbacks on main thread
        mainHandler.post {
            logCallback?.invoke(line)
        }
    }

    /** Wipe all registered callbacks (call in onDestroy) */
    fun clearCallbacks() {
        logCallback  = null
        chatCallback = null
    }

    /** Full log dump for crash reporting */
    fun dump(): String = buffer.joinToString("\n")

    /** Clear buffer */
    fun clear() { buffer.clear() }
}
