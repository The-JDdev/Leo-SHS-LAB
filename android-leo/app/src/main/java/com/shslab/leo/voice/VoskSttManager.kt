package com.shslab.leo.voice

import android.content.Context
import com.shslab.leo.core.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════
 *  VOSK OFFLINE STT MANAGER
 *
 *  Auto-downloads the small Vosk English model
 *  (vosk-model-small-en-us-0.15, ~40MB) for offline
 *  speech recognition. Until the native Vosk lib is
 *  bundled, Leo continues using Android SpeechRecognizer
 *  (which is already wired up in SpeechManager).
 * ══════════════════════════════════════════
 */
object VoskSttManager {

    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL  =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    @Volatile var modelReady = false
        private set
    @Volatile var downloading = false
        private set

    fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk").apply { if (!exists()) mkdirs() }
    fun modelZip(ctx: Context) = File(modelDir(ctx), "$MODEL_NAME.zip")

    fun isInstalled(ctx: Context): Boolean {
        val z = modelZip(ctx)
        val ok = z.exists() && z.length() > 10_000_000
        if (ok) modelReady = true
        return ok
    }

    fun ensureDownloadedAsync(ctx: Context) {
        if (isInstalled(ctx) || downloading) return
        downloading = true
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(180, TimeUnit.SECONDS)
                    .build()
                Logger.system("[Vosk] Downloading offline STT model (~40MB)...")
                client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val body = r.body ?: throw RuntimeException("empty body")
                    val tmp = File(modelDir(ctx), "$MODEL_NAME.zip.part")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
                    tmp.renameTo(modelZip(ctx))
                }
                modelReady = isInstalled(ctx)
                Logger.system("[Vosk] ✓ STT model ready: $modelReady")
            } catch (t: Throwable) {
                Logger.warn("[Vosk] Download failed: ${t.message} — using Android STT fallback")
            } finally { downloading = false }
        }.start()
    }
}
