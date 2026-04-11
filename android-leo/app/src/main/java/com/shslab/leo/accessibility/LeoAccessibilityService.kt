package com.shslab.leo.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════
 *  LEO ACCESSIBILITY SERVICE — SHS LAB v3
 *  Level 5 God-Mode UI Control Layer
 *
 *  New in v3:
 *  • waitForNode()  — dynamic UI polling, adapts to any app speed
 *  • readScreenText() — full screen dump for AI context awareness
 * ══════════════════════════════════════════
 */
class LeoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LeoAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.system("Accessibility service: CONNECTED — Level 5 God Mode active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> { }
        }
    }

    override fun onInterrupt() {
        Logger.warn("Accessibility service interrupted.")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Logger.system("Accessibility service: OFFLINE")
    }

    // ════════════════════════════════════════════
    //  LEVEL 5: EYES — SCREEN CONTEXT READER
    // ════════════════════════════════════════════

    /**
     * Dump ALL visible text and content-descriptions from the current screen.
     * Returns a structured multi-line string so AI can see what's on screen.
     * This is Leo's vision system — makes him context-aware in ANY app.
     *
     * @return Formatted screen dump, or error string if window not available
     */
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: run {
            Logger.warn("[EYES] No active window — screen read failed")
            return "screen_read_failed:no_active_window"
        }

        val sb = StringBuilder()
        sb.appendLine("=== SCREEN CONTEXT DUMP ===")
        collectNodeText(root, sb, depth = 0)
        root.recycle()

        val result = sb.toString().trim()
        val lineCount = result.lines().size
        Logger.action("[EYES] Screen dump: $lineCount elements captured")

        return if (result.length > 50) result.take(4000) else "screen_empty:no_visible_text"
    }

    /**
     * Recursively traverse the node tree and collect all text.
     */
    private fun collectNodeText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = node.hintText?.toString()?.trim()
        val id   = node.viewIdResourceName?.substringAfterLast("/")?.trim()

        when {
            !text.isNullOrBlank()  -> sb.appendLine("[TEXT${if (id != null) " id=$id" else ""}] $text")
            !desc.isNullOrBlank()  -> sb.appendLine("[DESC${if (id != null) " id=$id" else ""}] $desc")
            !hint.isNullOrBlank()  -> sb.appendLine("[HINT] $hint")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeText(child, sb, depth + 1)
            child.recycle()
        }
    }

    // ════════════════════════════════════════════
    //  LEVEL 5: PATIENCE — DYNAMIC WAIT_FOR
    // ════════════════════════════════════════════

    /**
     * Poll the accessibility node tree every 500ms until the target element appears.
     * Adapts to any app's load time — if network is slow, waits longer; if fast, returns immediately.
     *
     * @param target  Text content OR view ID to search for
     * @param timeoutMs  Maximum wait in milliseconds (default 10000)
     * @return "found:<target>:<elapsed_ms>ms" or "timeout:<target>"
     */
    fun waitForNode(target: String, timeoutMs: Long = 10000L): String {
        val startTime    = System.currentTimeMillis()
        val pollInterval = 500L

        Logger.action("[WAIT] Watching for '$target' (timeout: ${timeoutMs}ms, poll: ${pollInterval}ms)")

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null) {
                val node = findNode(root, textContent = target, viewId = target)
                if (node != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    node.recycle()
                    root.recycle()
                    Logger.action("[WAIT] ✓ Found '$target' after ${elapsed}ms")
                    return "found:${target}:${elapsed}ms"
                }
                root.recycle()
            }
            Thread.sleep(pollInterval)
        }

        val elapsed = System.currentTimeMillis() - startTime
        Logger.warn("[WAIT] ✗ Timeout — '$target' not found after ${elapsed}ms")
        return "timeout:${target}:waited_${elapsed}ms"
    }

    // ════════════════════════════════════════════
    //  NODE TRAVERSAL & INTERACTION METHODS
    // ════════════════════════════════════════════

    fun findNodeAndClick(textContent: String? = null, viewId: String? = null): Boolean {
        val root = rootInActiveWindow ?: run {
            Logger.warn("Accessibility: no active window root")
            return false
        }
        val node = findNode(root, textContent, viewId)
        return if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            root.recycle()
            Logger.action("[ACT] UI_CLICK → text='$textContent' id='$viewId' result=$result")
            result
        } else {
            Logger.warn("Node not found: text='$textContent' id='$viewId'")
            root.recycle()
            false
        }
    }

    fun injectTextToNode(viewId: String? = null, textContent: String? = null, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, textContent, viewId)
        return if (node != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            var result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (!result) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("leo_inject", textToType))
                result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }

            Logger.action("[ACT] UI_TYPE → '${textToType.take(40)}' into id='$viewId' result=$result")
            node.recycle()
            root.recycle()
            result
        } else {
            Logger.warn("Text injection target not found: id='$viewId' text='$textContent'")
            root.recycle()
            false
        }
    }

    fun performScroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        return if (scrollable != null) {
            val action = when (direction.lowercase()) {
                "down"  -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "up"    -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "left"  -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else    -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val result = scrollable.performAction(action)
            Logger.action("[ACT] UI_SCROLL → $direction result=$result")
            scrollable.recycle()
            root.recycle()
            result
        } else {
            performGestureScroll(direction)
            root.recycle()
            true
        }
    }

    fun launchAppByPackage(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Logger.action("[UI] Launched app: $packageName")
                true
            } else {
                Logger.warn("[UI] App not installed or no launch intent: $packageName")
                false
            }
        } catch (e: Exception) {
            Logger.error("[UI] launchAppByPackage failed: ${e.message}")
            false
        }
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    // ── Internal Helpers ──

    internal fun findNode(
        root: AccessibilityNodeInfo,
        textContent: String?,
        viewId: String?
    ): AccessibilityNodeInfo? {
        if (!viewId.isNullOrEmpty()) {
            val byId = root.findAccessibilityNodeInfosByViewId(viewId)
            if (byId.isNotEmpty()) return byId[0]
        }
        if (!textContent.isNullOrEmpty()) {
            val byText = root.findAccessibilityNodeInfosByText(textContent)
            if (byText.isNotEmpty()) return byText[0]
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
            child.recycle()
        }
        return null
    }

    private fun performGestureScroll(direction: String) {
        val displayMetrics = resources.displayMetrics
        val width  = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val path = Path()
        when (direction.lowercase()) {
            "down"  -> { path.moveTo(width / 2, height * 0.7f); path.lineTo(width / 2, height * 0.3f) }
            "up"    -> { path.moveTo(width / 2, height * 0.3f); path.lineTo(width / 2, height * 0.7f) }
            "right" -> { path.moveTo(width * 0.2f, height / 2); path.lineTo(width * 0.8f, height / 2) }
            "left"  -> { path.moveTo(width * 0.8f, height / 2); path.lineTo(width * 0.2f, height / 2) }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
