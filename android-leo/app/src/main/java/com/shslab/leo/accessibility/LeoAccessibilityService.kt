package com.shslab.leo.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════
 *  LEO ACCESSIBILITY SERVICE — SHS LAB
 *  God-Mode UI Control Layer
 *
 *  Enables Leo to browse, click, type, and
 *  scroll ANY app on the device autonomously.
 * ══════════════════════════════════════════
 */
class LeoAccessibilityService : AccessibilityService() {

    companion object {
        /** Singleton reference — set on connection */
        @Volatile
        var instance: LeoAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.system("Accessibility service: CONNECTED — God Mode active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Phase 1 skeleton: capture events, dispatch to controller in Phase 2+
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Window changed — update context for next command cycle
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content changed — node tree updated
            }
        }
        // Full traversal logic implemented in AccessibilityController
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
    //  NODE TRAVERSAL & INTERACTION METHODS
    // ════════════════════════════════════════════

    /**
     * Find a node by text content or view ID and perform a CLICK action.
     * @return true if node was found and clicked
     */
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

    /**
     * Inject text into a focused input node.
     * Primary: ACTION_SET_TEXT | Fallback: ClipboardManager paste
     */
    fun injectTextToNode(viewId: String? = null, textContent: String? = null, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, textContent, viewId)
        return if (node != null) {
            // Try ACTION_SET_TEXT first (API 21+)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            var result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            // Clipboard fallback
            if (!result) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("leo_inject", textToType))
                result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }

            Logger.action("[ACT] UI_TYPE → '$textToType' into id='$viewId' result=$result")
            node.recycle()
            root.recycle()
            result
        } else {
            Logger.warn("Text injection target not found: id='$viewId'")
            root.recycle()
            false
        }
    }

    /**
     * Perform a scroll action in a given direction.
     * @param direction "up" | "down" | "left" | "right"
     */
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
            // Fallback: gesture-based swipe
            performGestureScroll(direction)
            root.recycle()
            true
        }
    }

    /**
     * Press the device BACK button
     */
    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * Press the HOME button
     */
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    // ── Internal Helpers ──

    private fun findNode(
        root: AccessibilityNodeInfo,
        textContent: String?,
        viewId: String?
    ): AccessibilityNodeInfo? {
        // Try viewId first (more precise)
        if (!viewId.isNullOrEmpty()) {
            val byId = root.findAccessibilityNodeInfosByViewId(viewId)
            if (byId.isNotEmpty()) return byId[0]
        }
        // Try text content
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
