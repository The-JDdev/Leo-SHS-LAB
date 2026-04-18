package com.shslab.leo.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shslab.leo.core.Logger
import com.shslab.leo.memory.MemoryManager
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════
 *  SOCIAL MEDIA + BROWSER WATCHER (WorkManager)
 *
 *  Periodic background sweep ported from Doraemon's
 *  browser-watcher: scans recent system notifications
 *  + clipboard hints already captured in memory and
 *  generates contextual "follow-ups" (logged for the
 *  AI brain to pick up next session).
 * ══════════════════════════════════════════
 */
class SocialMediaWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val recent = MemoryManager.recall("notification message social", topN = 5)
            if (recent.isNotEmpty()) {
                Logger.system("[BgWatcher] swept ${recent.size} recent context items")
                MemoryManager.store(
                    kind = "episodic",
                    content = "[bg-sweep] reviewed ${recent.size} signals at " +
                              java.text.SimpleDateFormat("HH:mm").format(java.util.Date()),
                    importance = 2
                )
            }
            Result.success()
        } catch (t: Throwable) {
            Logger.warn("[BgWatcher] ${t.message}")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE = "leo_social_watcher"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SocialMediaWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req
            )
            Logger.system("[BgWatcher] scheduled every 30 min")
        }
    }
}
