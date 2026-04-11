package com.shslab.leo.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * ══════════════════════════════════════════
 *  LEO SECURITY VAULT — SHS LAB
 *  AES-256 GCM encrypted key storage.
 *  All AI credentials live exclusively here.
 * ══════════════════════════════════════════
 */
object SecurityManager {

    private const val PREFS_FILE = "leo_vault"

    // ── Vault Keys ──
    const val KEY_OPENROUTER_API    = "openrouter_api_key"
    const val KEY_OPENROUTER_ENDPOINT = "openrouter_endpoint"
    const val KEY_OPENROUTER_MODEL  = "openrouter_model"

    const val KEY_GEMINI_API        = "gemini_api_key"
    const val KEY_GEMINI_ENDPOINT   = "gemini_endpoint"
    const val KEY_GEMINI_MODEL      = "gemini_model"

    const val KEY_CLAUDE_API        = "claude_api_key"
    const val KEY_CLAUDE_ENDPOINT   = "claude_endpoint"
    const val KEY_CLAUDE_MODEL      = "claude_model"

    const val KEY_OPENAI_API        = "openai_api_key"
    const val KEY_OPENAI_ENDPOINT   = "openai_endpoint"
    const val KEY_OPENAI_MODEL      = "openai_model"

    const val KEY_GITHUB_TOKEN      = "github_token"
    const val KEY_ACTIVE_PROVIDER   = "active_ai_provider"

    // Defaults
    private const val DEFAULT_OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val DEFAULT_GEMINI_ENDPOINT     = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val DEFAULT_CLAUDE_ENDPOINT     = "https://api.anthropic.com/v1/messages"
    private const val DEFAULT_OPENAI_ENDPOINT     = "https://api.openai.com/v1/chat/completions"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Initialize the encrypted vault. Call once in Application.onCreate().
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // Seed defaults on first run
            seedDefaults()
        }
    }

    private fun seedDefaults() {
        val p = prefs ?: return
        if (!p.contains(KEY_OPENROUTER_ENDPOINT))
            p.edit().putString(KEY_OPENROUTER_ENDPOINT, DEFAULT_OPENROUTER_ENDPOINT).apply()
        if (!p.contains(KEY_GEMINI_ENDPOINT))
            p.edit().putString(KEY_GEMINI_ENDPOINT, DEFAULT_GEMINI_ENDPOINT).apply()
        if (!p.contains(KEY_CLAUDE_ENDPOINT))
            p.edit().putString(KEY_CLAUDE_ENDPOINT, DEFAULT_CLAUDE_ENDPOINT).apply()
        if (!p.contains(KEY_OPENAI_ENDPOINT))
            p.edit().putString(KEY_OPENAI_ENDPOINT, DEFAULT_OPENAI_ENDPOINT).apply()
        if (!p.contains(KEY_ACTIVE_PROVIDER))
            p.edit().putString(KEY_ACTIVE_PROVIDER, "openrouter").apply()
    }

    fun store(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun retrieve(key: String, default: String = ""): String {
        return prefs?.getString(key, default) ?: default
    }

    fun delete(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }

    // ── Convenience accessors ──

    fun getActiveProvider(): String = retrieve(KEY_ACTIVE_PROVIDER, "openrouter")

    fun getActiveApiKey(): String = when (getActiveProvider()) {
        "gemini"  -> retrieve(KEY_GEMINI_API)
        "claude"  -> retrieve(KEY_CLAUDE_API)
        "openai"  -> retrieve(KEY_OPENAI_API)
        else      -> retrieve(KEY_OPENROUTER_API)
    }

    fun getActiveEndpoint(): String = when (getActiveProvider()) {
        "gemini"  -> retrieve(KEY_GEMINI_ENDPOINT, DEFAULT_GEMINI_ENDPOINT)
        "claude"  -> retrieve(KEY_CLAUDE_ENDPOINT, DEFAULT_CLAUDE_ENDPOINT)
        "openai"  -> retrieve(KEY_OPENAI_ENDPOINT, DEFAULT_OPENAI_ENDPOINT)
        else      -> retrieve(KEY_OPENROUTER_ENDPOINT, DEFAULT_OPENROUTER_ENDPOINT)
    }

    fun getActiveModel(): String = when (getActiveProvider()) {
        "gemini"  -> retrieve(KEY_GEMINI_MODEL, "gemini-pro")
        "claude"  -> retrieve(KEY_CLAUDE_MODEL, "claude-3-haiku-20240307")
        "openai"  -> retrieve(KEY_OPENAI_MODEL, "gpt-4o-mini")
        else      -> retrieve(KEY_OPENROUTER_MODEL, "mistralai/mistral-7b-instruct")
    }

    fun getGitHubToken(): String = retrieve(KEY_GITHUB_TOKEN)

    /** Wipe all vault data (emergency reset) */
    fun nukeVault() {
        prefs?.edit()?.clear()?.apply()
    }
}
