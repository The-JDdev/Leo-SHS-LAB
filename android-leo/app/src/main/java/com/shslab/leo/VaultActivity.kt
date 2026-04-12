package com.shslab.leo

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.shslab.leo.security.SecurityManager

/**
 * ══════════════════════════════════════════════════════
 *  LEO VAULT — APEX v5
 *  SHS LAB — Encrypted Config + Customization
 *
 *  New in v5:
 *   - Agent name (custom — shown in header)
 *   - Dark / Light mode toggle
 *   - Voice Input (STT) toggle
 *   - TTS (Voice Output) toggle
 * ══════════════════════════════════════════════════════
 */
class VaultActivity : AppCompatActivity() {

    private lateinit var etAgentName: EditText
    private lateinit var switchDarkMode: Switch
    private lateinit var switchVoiceInput: Switch
    private lateinit var switchTTS: Switch

    private lateinit var rgProvider: RadioGroup
    private lateinit var rbOpenRouter: RadioButton
    private lateinit var rbOpenAI: RadioButton
    private lateinit var rbClaude: RadioButton
    private lateinit var rbGemini: RadioButton

    private lateinit var etApiKey: EditText
    private lateinit var etModelId: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etGithubToken: EditText

    private lateinit var btnToggleApiKeyVisibility: Button
    private lateinit var btnSaveVault: Button
    private lateinit var btnNukeVault: Button
    private lateinit var tvVaultStatus: TextView

    private var apiKeyVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)
        supportActionBar?.title = "Leo Vault"

        bindViews()
        loadVaultIntoUI()
        wireListeners()
    }

    private fun bindViews() {
        etAgentName              = findViewById(R.id.etAgentName)
        switchDarkMode           = findViewById(R.id.switchDarkMode)
        switchVoiceInput         = findViewById(R.id.switchVoiceInput)
        switchTTS                = findViewById(R.id.switchTTS)

        rgProvider               = findViewById(R.id.rgProvider)
        rbOpenRouter             = findViewById(R.id.rbOpenRouter)
        rbOpenAI                 = findViewById(R.id.rbOpenAI)
        rbClaude                 = findViewById(R.id.rbClaude)
        rbGemini                 = findViewById(R.id.rbGemini)

        etApiKey                  = findViewById(R.id.etApiKey)
        etModelId                 = findViewById(R.id.etModelId)
        etBaseUrl                 = findViewById(R.id.etBaseUrl)
        etGithubToken             = findViewById(R.id.etGithubToken)

        btnToggleApiKeyVisibility = findViewById(R.id.btnToggleApiKeyVisibility)
        btnSaveVault              = findViewById(R.id.btnSaveVault)
        btnNukeVault              = findViewById(R.id.btnNukeVault)
        tvVaultStatus             = findViewById(R.id.tvVaultStatus)
    }

    private fun loadVaultIntoUI() {
        // Customization
        etAgentName.setText(SecurityManager.getAgentName())
        switchDarkMode.isChecked   = SecurityManager.isDarkMode()
        switchVoiceInput.isChecked = SecurityManager.isVoiceEnabled()
        switchTTS.isChecked        = SecurityManager.isTTSEnabled()

        // Provider
        val provider = SecurityManager.getActiveProvider()
        when (provider) {
            "openai"  -> rbOpenAI.isChecked     = true
            "claude"  -> rbClaude.isChecked     = true
            "gemini"  -> rbGemini.isChecked     = true
            else      -> rbOpenRouter.isChecked = true
        }

        populateFieldsForProvider(provider)

        val githubToken = SecurityManager.getGitHubToken()
        if (githubToken.isNotBlank()) etGithubToken.setText(githubToken)

        updateStatusLabel(provider)
    }

    private fun populateFieldsForProvider(provider: String) {
        val (apiKey, model, endpoint) = when (provider) {
            "openai" -> Triple(
                SecurityManager.retrieve(SecurityManager.KEY_OPENAI_API),
                SecurityManager.retrieve(SecurityManager.KEY_OPENAI_MODEL, "gpt-4o-mini"),
                SecurityManager.retrieve(SecurityManager.KEY_OPENAI_ENDPOINT, "https://api.openai.com/v1/chat/completions")
            )
            "claude" -> Triple(
                SecurityManager.retrieve(SecurityManager.KEY_CLAUDE_API),
                SecurityManager.retrieve(SecurityManager.KEY_CLAUDE_MODEL, "claude-3-haiku-20240307"),
                SecurityManager.retrieve(SecurityManager.KEY_CLAUDE_ENDPOINT, "https://api.anthropic.com/v1/messages")
            )
            "gemini" -> Triple(
                SecurityManager.retrieve(SecurityManager.KEY_GEMINI_API),
                SecurityManager.retrieve(SecurityManager.KEY_GEMINI_MODEL, "gemini-pro"),
                SecurityManager.retrieve(SecurityManager.KEY_GEMINI_ENDPOINT, "https://generativelanguage.googleapis.com/v1beta/models")
            )
            else -> Triple(
                SecurityManager.retrieve(SecurityManager.KEY_OPENROUTER_API),
                SecurityManager.retrieve(SecurityManager.KEY_OPENROUTER_MODEL, "google/gemini-flash-1.5"),
                SecurityManager.retrieve(SecurityManager.KEY_OPENROUTER_ENDPOINT, "https://openrouter.ai/api/v1/chat/completions")
            )
        }
        etApiKey.setText(apiKey)
        etModelId.setText(model)
        etBaseUrl.setText(endpoint)
    }

    private fun wireListeners() {
        rgProvider.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                R.id.rbOpenAI -> "openai"
                R.id.rbClaude -> "claude"
                R.id.rbGemini -> "gemini"
                else          -> "openrouter"
            }
            populateFieldsForProvider(provider)
            updateStatusLabel(provider)
        }

        btnToggleApiKeyVisibility.setOnClickListener {
            apiKeyVisible = !apiKeyVisible
            if (apiKeyVisible) {
                etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                btnToggleApiKeyVisibility.text = "🙈"
            } else {
                etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                btnToggleApiKeyVisibility.text = "👁"
            }
            etApiKey.setSelection(etApiKey.text.length)
        }

        btnSaveVault.setOnClickListener { saveVault() }

        btnNukeVault.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Wipe Vault?")
                .setMessage("This permanently erases ALL keys and settings. Cannot be undone.")
                .setPositiveButton("WIPE") { _, _ ->
                    SecurityManager.nukeVault()
                    etAgentName.setText("")
                    etApiKey.setText("")
                    etModelId.setText("")
                    etBaseUrl.setText("")
                    etGithubToken.setText("")
                    setStatus("Vault wiped. All credentials erased.", "#FF4444")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun saveVault() {
        val agentName   = etAgentName.text.toString().trim()
        val apiKey      = etApiKey.text.toString().trim()
        val modelId     = etModelId.text.toString().trim()
        val baseUrl     = etBaseUrl.text.toString().trim()
        val githubToken = etGithubToken.text.toString().trim()
        val darkMode    = switchDarkMode.isChecked
        val voiceInput  = switchVoiceInput.isChecked
        val ttsOn       = switchTTS.isChecked

        if (apiKey.isBlank()) {
            setStatus("ERROR: API Key cannot be empty.", "#FF4444")
            etApiKey.requestFocus()
            return
        }

        // Customization prefs
        SecurityManager.setAgentName(agentName.ifEmpty { "Leo" })
        SecurityManager.setDarkMode(darkMode)
        SecurityManager.setVoiceEnabled(voiceInput)
        SecurityManager.setTTSEnabled(ttsOn)

        // Apply dark/light mode immediately
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        val provider = when {
            rbOpenAI.isChecked  -> "openai"
            rbClaude.isChecked  -> "claude"
            rbGemini.isChecked  -> "gemini"
            else                -> "openrouter"
        }

        SecurityManager.store(SecurityManager.KEY_ACTIVE_PROVIDER, provider)

        when (provider) {
            "openai" -> {
                SecurityManager.store(SecurityManager.KEY_OPENAI_API, apiKey)
                if (modelId.isNotBlank()) SecurityManager.store(SecurityManager.KEY_OPENAI_MODEL, modelId)
                if (baseUrl.isNotBlank()) SecurityManager.store(SecurityManager.KEY_OPENAI_ENDPOINT, baseUrl)
            }
            "claude" -> {
                SecurityManager.store(SecurityManager.KEY_CLAUDE_API, apiKey)
                if (modelId.isNotBlank()) SecurityManager.store(SecurityManager.KEY_CLAUDE_MODEL, modelId)
                if (baseUrl.isNotBlank()) SecurityManager.store(SecurityManager.KEY_CLAUDE_ENDPOINT, baseUrl)
            }
            "gemini" -> {
                SecurityManager.store(SecurityManager.KEY_GEMINI_API, apiKey)
                if (modelId.isNotBlank()) SecurityManager.store(SecurityManager.KEY_GEMINI_MODEL, modelId)
                if (baseUrl.isNotBlank()) SecurityManager.store(SecurityManager.KEY_GEMINI_ENDPOINT, baseUrl)
            }
            else -> {
                SecurityManager.store(SecurityManager.KEY_OPENROUTER_API, apiKey)
                if (modelId.isNotBlank()) SecurityManager.store(SecurityManager.KEY_OPENROUTER_MODEL, modelId)
                if (baseUrl.isNotBlank()) SecurityManager.store(SecurityManager.KEY_OPENROUTER_ENDPOINT, baseUrl)
            }
        }

        if (githubToken.isNotBlank()) {
            SecurityManager.store(SecurityManager.KEY_GITHUB_TOKEN, githubToken)
        }

        val displayName = agentName.ifEmpty { "Leo" }
        setStatus("Saved. Agent: $displayName | Provider: ${provider.uppercase()} | Theme: ${if (darkMode) "Dark" else "Light"}", "#00E5FF")

        btnSaveVault.postDelayed({ finish() }, 1200)
    }

    private fun updateStatusLabel(provider: String) {
        val key    = SecurityManager.getActiveApiKey()
        val hasKey = key.isNotBlank()
        val p      = provider.uppercase()
        if (hasKey) {
            setStatus("$p key loaded (${key.length} chars). Save to apply changes.", "#00E5FF")
        } else {
            setStatus("$p: No key stored. Enter your API key above.", "#FFD600")
        }
    }

    private fun setStatus(msg: String, colorHex: String) {
        tvVaultStatus.text = msg
        tvVaultStatus.setTextColor(android.graphics.Color.parseColor(colorHex))
    }
}
