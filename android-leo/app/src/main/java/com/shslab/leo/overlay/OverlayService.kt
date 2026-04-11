package com.shslab.leo.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.shslab.leo.R
import com.shslab.leo.core.Logger
import com.shslab.leo.executor.ActionExecutor

/**
 * ══════════════════════════════════════════
 *  LEO DYNAMIC ISLAND OVERLAY — SHS LAB
 *
 *  TYPE_APPLICATION_OVERLAY floating bubble.
 *  Draggable. Pulsating. Kill-switch enabled.
 * ══════════════════════════════════════════
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var pulseAnimator: ObjectAnimator? = null

    companion object {
        private const val NOTIF_CHANNEL = "leo_overlay_channel"
        private const val NOTIF_ID      = 1001

        @Volatile var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        inflateOverlay()
        isRunning = true
        Logger.system("Dynamic Island overlay: DEPLOYED")
    }

    private fun inflateOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 80
        }

        setupDragBehavior(params)
        setupKillSwitch()
        startPulseAnimation()

        windowManager.addView(overlayView, params)
    }

    private fun setupDragBehavior(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupKillSwitch() {
        overlayView?.findViewById<View>(R.id.btnKillSwitch)?.setOnClickListener {
            Logger.warn("[Leo]: Kill switch triggered — purging all tasks.")
            ActionExecutor.killAllTasks()
            System.gc()
            Runtime.getRuntime().freeMemory()
            Logger.system("[Leo]: Queue cleared, RAM freed.")
            stopSelf()
        }
    }

    private fun startPulseAnimation() {
        val bubble = overlayView?.findViewById<View>(R.id.overlayBubble) ?: return
        pulseAnimator = ObjectAnimator.ofFloat(bubble, "alpha", 0.6f, 1.0f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            NOTIF_CHANNEL,
            "Leo Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Leo Dynamic Island overlay running" }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Leo — SHS LAB")
            .setContentText("Dynamic Island active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        pulseAnimator?.cancel()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        Logger.system("Overlay service terminated.")
        super.onDestroy()
    }
}
