package com.shslab.leo

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shslab.leo.accessibility.LeoAccessibilityService
import com.shslab.leo.core.Logger
import com.shslab.leo.executor.ActionExecutor
import com.shslab.leo.network.LeoNetworkClient
import com.shslab.leo.overlay.OverlayService
import com.shslab.leo.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════
 *  LEO MAIN TERMINAL — SHS LAB
 *  Pitch-black God-Mode Command Interface
 * ══════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalTextView: TextView
    private lateinit var terminalScrollView: ScrollView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var btnIsland: Button
    private lateinit var btnVault: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnEnableOverlay: Button
    private lateinit var dotAccessibility: View
    private lateinit var dotOverlay: View
    private lateinit var logoImageView: ImageView

    private val networkClient by lazy { LeoNetworkClient() }
    private val actionExecutor by lazy { ActionExecutor(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Encrypted vault must be unlocked before anything else
        SecurityManager.init(this)

        setContentView(R.layout.activity_main)

        bindViews()
        Logger.attach(terminalTextView, terminalScrollView)
        bootSequence()
    }

    private fun bindViews() {
        logoImageView          = findViewById(R.id.imgLeoLogo)
        terminalScrollView     = findViewById(R.id.scrollTerminal)
        terminalTextView       = findViewById(R.id.tvTerminal)
        inputEditText          = findViewById(R.id.etInput)
        sendButton             = findViewById(R.id.btnSend)
        btnIsland              = findViewById(R.id.btnIsland)
        btnVault               = findViewById(R.id.btnVault)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableOverlay       = findViewById(R.id.btnEnableOverlay)
        dotAccessibility       = findViewById(R.id.dotAccessibility)
        dotOverlay             = findViewById(R.id.dotOverlay)

        sendButton.setOnClickListener { dispatchUserCommand() }
        btnIsland.setOnClickListener  { toggleOverlay() }
        btnVault.setOnClickListener   { openVault() }

        // Tapping ENABLE fires the real Android system settings intent
        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            Logger.system("[SETUP] Redirecting to Accessibility Settings...")
        }

        btnEnableOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            Logger.system("[SETUP] Redirecting to Overlay Permission Settings...")
        }

        // IME send action on keyboard
        inputEditText.setOnEditorActionListener { _, _, _ ->
            dispatchUserCommand()
            true
        }
    }

    private fun bootSequence() {
        Logger.system("════════════════════════════════")
        Logger.system("  LEO v1.0.0 — SHS LAB ONLINE  ")
        Logger.system("════════════════════════════════")
        Logger.system("Kernel boot sequence initiated...")
        Logger.leo("Identity protocol loaded.")
        Logger.system("Encrypted vault: UNLOCKED")
        Logger.system("Active provider: ${SecurityManager.getActiveProvider().uppercase()}")

        // Check if API key is actually configured
        val apiKey = SecurityManager.getActiveApiKey()
        if (apiKey.isBlank()) {
            Logger.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Logger.warn("  NO API KEY CONFIGURED")
            Logger.warn("  Tap ⚙ VAULT to enter your key.")
            Logger.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        } else {
            Logger.system("API key: LOADED (${apiKey.length} chars)")
            Logger.system("Ready for operator input.")
        }

        Logger.raw("────────────────────────────────")
    }

    /**
     * Called every time the user returns to this screen.
     * Refreshes permission status dots live.
     */
    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    /**
     * Check both permissions and update the green/red status dots + button states.
     */
    private fun refreshPermissionStatus() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay       = Settings.canDrawOverlays(this)

        val green = 0xFF00C853.toInt()
        val red   = 0xFFFF4444.toInt()

        // Set oval dot color while keeping the shape (GradientDrawable keeps it round)
        fun setDotColor(dot: View, color: Int) {
            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(color)
            dot.background = drawable
        }

        setDotColor(dotAccessibility, if (hasAccessibility) green else red)
        btnEnableAccessibility.text = if (hasAccessibility) "✓ ON" else "ENABLE"
        btnEnableAccessibility.backgroundTintList = ColorStateList.valueOf(
            if (hasAccessibility) green else red
        )
        btnEnableAccessibility.isEnabled = !hasAccessibility

        setDotColor(dotOverlay, if (hasOverlay) green else red)
        btnEnableOverlay.text = if (hasOverlay) "✓ ON" else "ENABLE"
        btnEnableOverlay.backgroundTintList = ColorStateList.valueOf(
            if (hasOverlay) green else red
        )
        btnEnableOverlay.isEnabled = !hasOverlay
    }

    /**
     * Check if our LeoAccessibilityService is actually running.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val componentName = "$packageName/${LeoAccessibilityService::class.java.name}"
        return enabledServices.contains(componentName, ignoreCase = true)
    }

    /**
     * Open the Vault settings Activity so the operator can configure API keys.
     */
    private fun openVault() {
        startActivity(Intent(this, VaultActivity::class.java))
    }

    /**
     * Core command dispatcher.
     * Reads input → sends to AI via LeoNetworkClient → pipes response to ActionExecutor.
     */
    private fun dispatchUserCommand() {
        val userInput = inputEditText.text.toString().trim()
        if (userInput.isEmpty()) return

        // Guard: must have API key before dispatching
        val apiKey = SecurityManager.getActiveApiKey()
        if (apiKey.isBlank()) {
            Logger.error("No API key configured. Tap ⚙ VAULT to set one.")
            Logger.warn("Press VAULT → enter your API key → SAVE.")
            return
        }

        inputEditText.setText("")
        sendButton.isEnabled = false

        Logger.leo("Operator → $userInput")
        Logger.net("[Leo]: Establishing direct uplink...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = networkClient.sendPrompt(userInput)
                withContext(Dispatchers.Main) {
                    actionExecutor.execute(response)
                    sendButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Logger.error("Uplink failure: ${e.message}")
                    sendButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Start/stop the Dynamic Island overlay bubble.
     */
    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Logger.warn("[OVERLAY] SYSTEM_ALERT_WINDOW not granted — tap ENABLE in the bar above.")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val svc = Intent(this, OverlayService::class.java)
        startForegroundService(svc)
        Logger.system("[OVERLAY] Dynamic Island activated.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.detach()
    }
}
