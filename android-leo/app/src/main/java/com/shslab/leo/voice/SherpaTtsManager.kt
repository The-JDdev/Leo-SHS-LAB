package com.shslab.leo.voice

import android.content.Context
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════
 *  SHERPA-ONNX TTS MANAGER (Piper VITS bridge)
 *
 *  Tries native Sherpa-ONNX engine first (when JNI .so
 *  libs are present + Piper model downloaded).
 *  Falls back to existing Android SpeechManager which
 *  already produces a warm female voice.
 *
 *  This keeps the build green on devices/builds where
 *  the native libs aren't bundled, while exposing the
 *  exact same `speak(...)` API to the rest of Leo.
 * ══════════════════════════════════════════
 */
object SherpaTtsManager {

    @Volatile private var engineAvailable = false
    @Volatile private var lastAmplitude = 0f

    fun init(ctx: Context) {
        // Kick off the model download (idempotent)
        PiperModelDownloader.ensureDownloadedAsync(ctx)
        // Probe for native lib presence (libsherpa-onnx-jni.so)
        engineAvailable = try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (_: Throwable) { false }
        Logger.system("[SherpaTTS] native engine = $engineAvailable, " +
            "model installed = ${PiperModelDownloader.isInstalled(ctx)}")
    }

    /** Returns true if Doraemon should use the high-quality neural voice path. */
    fun isHighQualityReady(ctx: Context): Boolean =
        engineAvailable && PiperModelDownloader.isInstalled(ctx)

    /** Latest mic/voice amplitude 0..1 — read by the Siri bubble for animation. */
    fun reportAmplitude(amp: Float) { lastAmplitude = amp.coerceIn(0f, 1f) }
    fun amplitude(): Float = lastAmplitude

    /**
     * Speak via Sherpa-ONNX if available. Returns false if caller should
     * fall back to SpeechManager (which is the default working path).
     */
    fun speak(ctx: Context, text: String): Boolean {
        if (!isHighQualityReady(ctx)) return false
        // When user bundles libsherpa-onnx-jni.so + java bindings, route here.
        // Implementation is intentionally a stub so the APK builds standalone.
        Logger.system("[SherpaTTS] would speak via Piper VITS: ${text.take(60)}")
        return false
    }
}
