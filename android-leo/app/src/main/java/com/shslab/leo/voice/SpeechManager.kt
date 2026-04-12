package com.shslab.leo.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.shslab.leo.core.Logger
import java.util.Locale
import java.util.UUID

/**
 * ══════════════════════════════════════════════════════
 *  LEO SPEECH MANAGER — STT + TTS
 *  SHS LAB v5 — APEX ARCHITECTURE
 *
 *  Speech-to-Text: Android SpeechRecognizer API
 *  Text-to-Speech: Android TextToSpeech engine
 *
 *  Leo's voice: warm, loyal to JD, but powerful.
 * ══════════════════════════════════════════════════════
 */
class SpeechManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsReady = false

    init {
        initTTS()
    }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(0.9f)
                ttsReady = true
                Logger.system("[VOICE] TTS engine ready")
            } else {
                Logger.warn("[VOICE] TTS init failed: $status")
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  TEXT-TO-SPEECH
    // ══════════════════════════════════════════════════

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ttsReady || tts == null) {
            Logger.warn("[VOICE] TTS not ready")
            onDone?.invoke()
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            override fun onError(utteranceId: String?) { onDone?.invoke() }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Logger.action("[VOICE] Speaking: ${text.take(60)}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking ?: false

    // ══════════════════════════════════════════════════
    //  SPEECH-TO-TEXT
    // ══════════════════════════════════════════════════

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("speech_recognition_not_available")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Logger.action("[VOICE] Listening...")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Logger.action("[VOICE] End of speech")
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO          -> "audio_error"
                    SpeechRecognizer.ERROR_CLIENT         -> "client_error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no_mic_permission"
                    SpeechRecognizer.ERROR_NETWORK        -> "network_error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
                    SpeechRecognizer.ERROR_NO_MATCH       -> "no_match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
                    SpeechRecognizer.ERROR_SERVER         -> "server_error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
                    else                                  -> "unknown_error_$error"
                }
                Logger.warn("[VOICE] STT Error: $msg")
                onError(msg)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Logger.action("[VOICE] Recognized: $text")
                if (text.isNotBlank()) onResult(text) else onError("empty_result")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (partial.isNotBlank()) onPartialResult?.invoke(partial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
