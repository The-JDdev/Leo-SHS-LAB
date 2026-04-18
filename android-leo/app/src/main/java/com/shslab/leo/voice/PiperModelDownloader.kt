package com.shslab.leo.voice

import android.content.Context
import com.shslab.leo.core.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════
 *  PIPER VITS INT8 MODEL DOWNLOADER
 *
 *  Auto-fetches Piper TTS model on first run
 *  from Hugging Face → app's internal storage.
 *  INT8 quantized → ~25MB, fits 2GB RAM device.
 * ══════════════════════════════════════════
 */
object PiperModelDownloader {

    private const val MODEL_FILENAME  = "en_US-amy-medium.int8.onnx"
    private const val TOKENS_FILENAME = "tokens.txt"

    // Hugging Face mirror (Piper VITS quantized voices)
    // amy-medium = warm, friendly female voice — perfect for Doraemon
    private const val MODEL_URL =
        "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-medium/resolve/main/en_US-amy-medium.onnx"
    private const val TOKENS_URL =
        "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-medium/resolve/main/tokens.txt"

    @Volatile var ready = false
        private set
    @Volatile var downloading = false
        private set

    fun modelDir(ctx: Context): File {
        val d = File(ctx.filesDir, "piper-tts")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun modelFile(ctx: Context)  = File(modelDir(ctx), MODEL_FILENAME)
    fun tokensFile(ctx: Context) = File(modelDir(ctx), TOKENS_FILENAME)

    fun isInstalled(ctx: Context): Boolean {
        val ok = modelFile(ctx).exists() && modelFile(ctx).length() > 1_000_000 &&
                 tokensFile(ctx).exists()
        if (ok) ready = true
        return ok
    }

    /** Idempotent. Safe to call from app start. */
    fun ensureDownloadedAsync(ctx: Context) {
        if (isInstalled(ctx) || downloading) return
        downloading = true
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                Logger.system("[Piper] Downloading TTS model (~25MB)...")
                downloadTo(client, MODEL_URL,  modelFile(ctx))
                Logger.system("[Piper] Downloading tokens...")
                downloadTo(client, TOKENS_URL, tokensFile(ctx))

                ready = isInstalled(ctx)
                if (ready) Logger.system("[Piper] ✓ Voice model installed (${modelFile(ctx).length()/1024} KB)")
                else       Logger.warn("[Piper] Install verification failed")
            } catch (t: Throwable) {
                Logger.warn("[Piper] Download failed: ${t.message} — will use system TTS fallback")
            } finally {
                downloading = false
            }
        }.start()
    }

    private fun downloadTo(client: OkHttpClient, url: String, dst: File) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw RuntimeException("empty body for $url")
            val tmp = File(dst.parentFile, dst.name + ".part")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out, bufferSize = 64 * 1024) }
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true); tmp.delete()
            }
        }
    }
}
