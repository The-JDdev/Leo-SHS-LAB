package com.shslab.leo.voice

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════════════════
 *  LEO VOICE INTERACTION SERVICE
 *  SHS LAB v5 — APEX ARCHITECTURE
 *
 *  Registers Leo as a default Android Assistant.
 *  When the user holds Home or says "Hey Assistant",
 *  Leo intercepts and handles the request.
 * ══════════════════════════════════════════════════════
 */
class LeoVoiceService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Logger.system("[VOICE_SVC] Leo Voice Interaction Service ready — Leo is the default assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        Logger.warn("[VOICE_SVC] Voice service shutdown")
    }
}
