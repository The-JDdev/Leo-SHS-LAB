package com.shslab.leo

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.shslab.leo.automation.SocialMediaWorker
import com.shslab.leo.core.Logger
import com.shslab.leo.memory.MemoryManager
import com.shslab.leo.overlay.SiriOverlayService
import com.shslab.leo.security.SecurityManager
import com.shslab.leo.voice.PiperModelDownloader
import com.shslab.leo.voice.SherpaTtsManager
import com.shslab.leo.voice.VoskSttManager

/**
 * ══════════════════════════════════════════
 *  LEO APPLICATION CLASS — SHS LAB
 *  Global initialization — runs before MainActivity.
 *
 *  v1.7-doraemon:
 *   - Boots RAG memory
 *   - Auto-downloads Piper VITS TTS + Vosk STT models
 *   - Schedules background social/notification watcher
 *   - Auto-starts the Siri-style overlay bubble (if permission granted)
 * ══════════════════════════════════════════
 */
class LeoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Encrypted vault (MUST be first)
        SecurityManager.init(this)

        // 2. Doraemon RAG memory
        MemoryManager.init(this)

        // 3. Voice subsystems — kick off model downloads on first launch
        SherpaTtsManager.init(this)
        PiperModelDownloader.ensureDownloadedAsync(this)
        VoskSttManager.ensureDownloadedAsync(this)

        // 4. Background social-media / browser watcher (WorkManager)
        try { SocialMediaWorker.schedule(this) } catch (t: Throwable) {
            Logger.warn("[App] worker schedule: ${t.message}")
        }

        // 5. Auto-start Siri overlay bubble if user has granted Draw-Over-Apps
        if (canDrawOverlays()) {
            try {
                val i = Intent(this, SiriOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
                else startService(i)
            } catch (t: Throwable) {
                Logger.warn("[App] siri overlay autostart: ${t.message}")
            }
        }

        Logger.system("[App] Doraemon online — ${MemoryManager.stats()}")
    }

    private fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(this)
    } catch (_: Throwable) { false }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.warn("[SYS] Low memory — clearing caches")
        System.gc()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            Logger.warn("[SYS] Trim level $level — releasing")
            System.gc()
        }
    }
}
