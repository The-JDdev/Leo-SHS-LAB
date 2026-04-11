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
 *  LEO ACCESSIBILITY SERVICE — SHS LAB v4
 *  Absolute Sovereignty — God-Mode UI Layer
 *
 *  Full sub_action support:
 *  OPEN_APP, CLICK, LONG_PRESS, TYPE, SCROLL,
 *  WAIT_FOR, BACK, HOME, READ_SCREEN
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
        Logger.system("Accessibility service: CONNECTED — Absolute Sovereignty active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED  -> { }
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
    //  EYES — SCREEN CONTEXT READER
    // ════════════════════════════════════════════

    /**
     * Full DFS dump of all visible text/IDs on the current screen.
     * Leo's vision system — works in ANY app without hardcoded macros.
     */
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: run {
            Logger.warn("[EYES] No active window")
            return "screen_read_failed:no_active_window"
        }
        val sb = StringBuilder()
        sb.appendLine("=== SCREEN DUMP ===")
        collectNodeText(root, sb)
        root.recycle()
        val result = sb.toString().trim()
        Logger.action("[EYES] Screen dump: ${result.lines().size} elements")
        return if (result.length > 50) result.take(4000) else "screen_empty:no_text_found"
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = node.hintText?.toString()?.trim()
        val id   = node.viewIdResourceName?.substringAfterLast("/")?.trim()

        when {
            !text.isNullOrBlank() -> sb.appendLine("[TEXT${if (id != null) " id=$id" else ""}] $text")
            !desc.isNullOrBlank() -> sb.appendLine("[DESC${if (id != null) " id=$id" else ""}] $desc")
            !hint.isNullOrBlank() -> sb.appendLine("[HINT] $hint")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeText(child, sb)
            child.recycle()
        }
    }

    // ════════════════════════════════════════════
    //  PATIENCE — DYNAMIC WAIT_FOR
    // ════════════════════════════════════════════

    /**
     * Poll the node tree every 500ms until target appears or timeout.
     * Adapts to any app's load speed — fast or slow network.
     */
    fun waitForNode(target: String, timeoutMs: Long = 10000L): String {
        val start        = System.currentTimeMillis()
        val pollInterval = 500L
        Logger.action("[WAIT] Watching for '$target' (timeout: ${timeoutMs}ms)")

        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null) {
                val node = findNode(root, target, target)
                if (node != null) {
                    val elapsed = System.currentTimeMillis() - start
                    node.recycle()
                    root.recycle()
                    Logger.action("[WAIT] ✓ Found '$target' after ${elapsed}ms")
                    return "found:${target}:${elapsed}ms"
                }
                root.recycle()
            }
            Thread.sleep(pollInterval)
        }

        val elapsed = System.currentTimeMillis() - start
        Logger.warn("[WAIT] ✗ Timeout — '$target' not found after ${elapsed}ms")
        return "timeout:${target}:${elapsed}ms"
    }

    // ════════════════════════════════════════════
    //  SUB_ACTION HANDLERS
    // ════════════════════════════════════════════

    /** CLICK — find node by text or ID and perform a click */
    fun findNodeAndClick(textContent: String? = null, viewId: String? = null): Boolean {
        val root = rootInActiveWindow ?: run {
            Logger.warn("[ACT] No active window for CLICK")
            return false
        }
        val node = findNode(root, textContent, viewId)
        return if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            root.recycle()
            Logger.action("[ACT] CLICK → text='$textContent' id='$viewId' result=$result")
            result
        } else {
            Logger.warn("[ACT] CLICK target not found: text='$textContent' id='$viewId'")
            root.recycle()
            false
        }
    }

    /** LONG_PRESS — find node and perform ACTION_LONG_CLICK */
    fun findNodeAndLongPress(textContent: String? = null, viewId: String? = null): Boolean {
        val root = rootInActiveWindow ?: run {
            Logger.warn("[ACT] No active window for LONG_PRESS")
            return false
        }
        val node = findNode(root, textContent, viewId)
        return if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            node.recycle()
            root.recycle()
            Logger.action("[ACT] LONG_PRESS → text='$textContent' result=$result")
            result
        } else {
            Logger.warn("[ACT] LONG_PRESS target not found: text='$textContent' id='$viewId'")
            root.recycle()
            // Fallback: gesture long press at center of screen
            performGestureLongPress()
            true
        }
    }

    /** TYPE — inject text into a focused input field */
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

            Logger.action("[ACT] TYPE → '${textToType.take(40)}' into id='$viewId' result=$result")
            node.recycle()
            root.recycle()
            result
        } else {
            // Fallback: try to set text on any focused/editable node
            Logger.warn("[ACT] TYPE target not found by id/text — scanning for editable node")
            val editable = findEditableNode(root)
            if (editable != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                }
                val result = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                editable.recycle()
                root.recycle()
                Logger.action("[ACT] TYPE → fallback editable node: result=$result")
                result
            } else {
                root.recycle()
                false
            }
        }
    }

    /** SCROLL — scroll the main scrollable container */
    fun performScroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        return if (scrollable != null) {
            val action = when (direction.lowercase()) {
                "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "up",  "left"   -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else            -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val result = scrollable.performAction(action)
            Logger.action("[ACT] SCROLL → $direction result=$result")
            scrollable.recycle()
            root.recycle()
            result
        } else {
            performGestureScroll(direction)
            root.recycle()
            true
        }
    }

    /** Launch any installed app by its package name */
    fun launchAppByPackage(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Logger.action("[APP] Launched: $packageName")
                true
            } else {
                Logger.warn("[APP] Not installed or no launcher: $packageName")
                false
            }
        } catch (e: Exception) {
            Logger.error("[APP] Launch failed: ${e.message}")
            false
        }
    }

    /** Press the hardware BACK button */
    fun pressBack(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_BACK)
        Logger.action("[ACT] BACK pressed → $result")
        return result
    }

    /** Press the HOME button */
    fun pressHome(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_HOME)
        Logger.action("[ACT] HOME pressed → $result")
        return result
    }

    // ════════════════════════════════════════════
    //  INTERNAL NODE FINDING
    // ════════════════════════════════════════════

    internal fun findNode(
        root: AccessibilityNodeInfo,
        textContent: String?,
        viewId: String?
    ): AccessibilityNodeInfo? {
        // Try full package:id/viewId first
        if (!viewId.isNullOrEmpty()) {
            val byId = root.findAccessibilityNodeInfosByViewId(viewId)
            if (byId.isNotEmpty()) return byId[0]
            // Try with partial match (just the id part, no package prefix)
            if (!viewId.contains("/")) {
                val withPkg = root.findAccessibilityNodeInfosByViewId("${packageName}:id/$viewId")
                if (withPkg.isNotEmpty()) return withPkg[0]
            }
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

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val editable = findEditableNode(child)
            if (editable != null) return editable
            child.recycle()
        }
        return null
    }

    // ════════════════════════════════════════════
    //  GESTURE FALLBACKS
    // ════════════════════════════════════════════

    private fun performGestureScroll(direction: String) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path()
        when (direction.lowercase()) {
            "down"  -> { path.moveTo(w/2, h*0.7f); path.lineTo(w/2, h*0.3f) }
            "up"    -> { path.moveTo(w/2, h*0.3f); path.lineTo(w/2, h*0.7f) }
            "right" -> { path.moveTo(w*0.2f, h/2); path.lineTo(w*0.8f, h/2) }
            "left"  -> { path.moveTo(w*0.8f, h/2); path.lineTo(w*0.2f, h/2) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performGestureLongPress() {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply { moveTo(w/2, h/2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000)) // 1s = long press
            .build()
        dispatchGesture(gesture, null, null)
    }
}
