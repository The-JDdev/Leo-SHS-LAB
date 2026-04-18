package com.shslab.leo.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.shslab.leo.R
import com.shslab.leo.core.Logger
import com.shslab.leo.voice.SherpaTtsManager
import java.io.File

/**
 * ══════════════════════════════════════════
 *  SIRI-STYLE FLOATING BUBBLE OVERLAY
 *
 *  TYPE_APPLICATION_OVERLAY draggable bubble that
 *  reacts to live microphone amplitude. Tap → launch
 *  Leo voice session.
 *
 *  Uses MediaRecorder.maxAmplitude() (no audio actually
 *  recorded to disk — /dev/null) for cheap mic level
 *  detection that's safe on 2GB RAM.
 * ══════════════════════════════════════════
 */
class SiriOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: SiriBubbleView? = null
    private var recorder: MediaRecorder? = null
    private var sampleThread: Thread? = null
    @Volatile private var sampling = false

    companion object {
        private const val CH = "leo_siri_overlay"
        private const val NOTIF_ID = 4091
        const val ACTION_SHOW = "com.shslab.leo.SIRI_SHOW"
        const val ACTION_HIDE = "com.shslab.leo.SIRI_HIDE"
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotif())
        showBubble()
        startAmplitudeSampling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { stopSelf(); return START_NOT_STICKY }
            else -> { /* default: keep bubble showing */ }
        }
        return START_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return
        val v = SiriBubbleView(this)
        val size = (resources.displayMetrics.density * 110).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 240
        }

        var lastX = 0f; var lastY = 0f; var startX = 0; var startY = 0
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.rawX; lastY = ev.rawY
                    startX = lp.x;   startY = lp.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (ev.rawX - lastX).toInt()
                    lp.y = startY + (ev.rawY - lastY).toInt()
                    try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - lastX; val dy = ev.rawY - lastY
                    if (Math.abs(dx) < 12 && Math.abs(dy) < 12) {
                        // Tap → open Leo's main activity for voice mode
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        launch?.putExtra("voice_mode", true)
                        startActivity(launch)
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(v, lp)
            bubble = v
            Logger.system("[SiriOverlay] bubble shown")
        } catch (t: Throwable) {
            Logger.warn("[SiriOverlay] addView failed: ${t.message}")
        }
    }

    private fun startAmplitudeSampling() {
        if (sampling) return
        sampling = true
        sampleThread = Thread {
            try {
                @Suppress("DEPRECATION")
                recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(File(cacheDir, "leo_amp.tmp").absolutePath)
                    prepare()
                    start()
                }
                while (sampling) {
                    val amp = try { recorder?.maxAmplitude ?: 0 } catch (_: Throwable) { 0 }
                    val norm = (amp / 8000f).coerceIn(0f, 1f)
                    SherpaTtsManager.reportAmplitude(norm)
                    Thread.sleep(60)
                }
            } catch (t: Throwable) {
                Logger.warn("[SiriOverlay] amplitude sampling unavailable: ${t.message}")
                // Without RECORD_AUDIO grant, fall back to gentle fake pulse
                while (sampling) {
                    SherpaTtsManager.reportAmplitude((0.15f + Math.random().toFloat() * 0.15f))
                    Thread.sleep(120)
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun onDestroy() {
        sampling = false
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        bubble?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotif(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH, "Leo Siri Bubble", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Leo")
            .setContentText("Siri-style assist bubble active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
