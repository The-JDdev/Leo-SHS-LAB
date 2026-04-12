package com.shslab.leo.voice

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.shslab.leo.MainActivity
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════════════════
 *  LEO VOICE INTERACTION SERVICE — SUPREME SOVEREIGN v6
 *  SHS LAB — TRUE ANDROID DEFAULT ASSISTANT
 *
 *  This is Leo's registration as Android's default voice
 *  assistant. When the user:
 *    - Long-presses the Home button
 *    - Says "Hey Assistant" (if hotword supported)
 *    - Uses the Android Assist gesture
 *  ...the OS launches this service and calls onLaunchVoiceAssistFromKeyguard
 *  or the session handler, which opens Leo's MainActivity.
 *
 *  To set Leo as default: Settings → Apps → Default apps →
 *  Digital assistant app → Leo
 * ══════════════════════════════════════════════════════
 */
class LeoVoiceService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Logger.system("[VOICE_SVC] Leo Voice Interaction Service READY")
        Logger.system("[VOICE_SVC] Leo is registered as Android Default Assistant")
        Logger.system("[VOICE_SVC] Long-press Home → Leo will intercept")
    }

    /**
     * Called when the user triggers the assistant from the lock screen.
     * Launch Leo's main activity to handle the voice command.
     */
    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        Logger.system("[VOICE_SVC] Keyguard voice assist triggered — launching Leo")
        launchLeoMain(fromAssist = true)
    }

    override fun onShutdown() {
        super.onShutdown()
        Logger.warn("[VOICE_SVC] Voice service shutdown")
    }

    private fun launchLeoMain(fromAssist: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (fromAssist) {
                putExtra("from_voice_assist", true)
            }
        }
        startActivity(intent)
    }
}
