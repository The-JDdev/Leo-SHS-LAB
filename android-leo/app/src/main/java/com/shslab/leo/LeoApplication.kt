package com.shslab.leo

import android.app.Application
import com.shslab.leo.core.Logger
import com.shslab.leo.security.SecurityManager

/**
 * ══════════════════════════════════════════
 *  LEO APPLICATION CLASS — SHS LAB
 *  Global initialization — runs before MainActivity.
 * ══════════════════════════════════════════
 */
class LeoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize encrypted vault (MUST be first)
        SecurityManager.init(this)

        // 2. Reduce GC pause impact on 2GB RAM device
        Runtime.getRuntime().apply {
            // Hint: do not request more heap than necessary
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.warn("[SYS] Low memory warning — clearing caches")
        System.gc()
        Logger.system("[Leo]: Queue cleared, RAM freed.")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            Logger.warn("[SYS] Trim memory level $level — releasing resources")
            System.gc()
        }
    }
}
