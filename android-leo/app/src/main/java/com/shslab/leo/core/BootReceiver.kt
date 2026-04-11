package com.shslab.leo.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shslab.leo.overlay.OverlayService

/**
 * Auto-starts Leo's overlay on device boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, OverlayService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
