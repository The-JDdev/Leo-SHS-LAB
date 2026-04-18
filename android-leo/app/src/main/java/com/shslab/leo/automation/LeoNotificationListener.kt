package com.shslab.leo.automation

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.shslab.leo.core.Logger
import com.shslab.leo.memory.MemoryManager

/**
 * ══════════════════════════════════════════
 *  NOTIFICATION GADGET — passive sentience
 *
 *  Captures incoming notifications (WhatsApp / SMS /
 *  email / etc) into RAG memory so Doraemon can
 *  recall them when JD asks "what did so-and-so say?"
 * ══════════════════════════════════════════
 */
class LeoNotificationListener : NotificationListenerService() {

    private val skipPackages = setOf(
        "com.shslab.leo",
        "android",
        "com.android.systemui"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val pkg = sbn.packageName ?: return
            if (pkg in skipPackages) return
            val ex = sbn.notification?.extras ?: return
            val title = ex.getCharSequence("android.title")?.toString().orEmpty()
            val text  = ex.getCharSequence("android.text")?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return

            val short = "[notif] $pkg • $title — ${text.take(140)}"
            MemoryManager.store("episodic", short, importance = 4)
            Logger.system("[Notif] captured: ${short.take(80)}")
        } catch (t: Throwable) {
            Logger.warn("[Notif] ${t.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* no-op */ }
}
