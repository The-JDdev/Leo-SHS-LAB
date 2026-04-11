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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shslab.leo.accessibility.LeoAccessibilityService
import com.shslab.leo.chat.ChatAdapter
import com.shslab.leo.core.Logger
import com.shslab.leo.executor.ActionExecutor
import com.shslab.leo.network.LeoNetworkClient
import com.shslab.leo.overlay.OverlayService
import com.shslab.leo.parser.CommandParser
import com.shslab.leo.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════
 *  LEO MAIN ACTIVITY — SHS LAB
 *  Modern Chat Interface with Thinking Accordion
 * ══════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

    // ── Views ──
    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnVault: Button
    private lateinit var btnIsland: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnEnableOverlay: Button
    private lateinit var dotAccessibility: View
    private lateinit var dotOverlay: View
    private lateinit var imgLeoLogo: ImageView

    // ── Chat engine ──
    private lateinit var chatAdapter: ChatAdapter

    // ── Backend ──
    private val networkClient by lazy { LeoNetworkClient() }
    private val actionExecutor by lazy { ActionExecutor(this) }

    // Track current pending Leo message ID for log routing
    @Volatile private var pendingLeoId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityManager.init(this)
        setContentView(R.layout.activity_main)
        bindViews()
        setupChat()
        wireInputs()
        bootGreeting()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.clearCallbacks()
    }

    // ══════════════════════════════════════════════════
    //  SETUP
    // ══════════════════════════════════════════════════

    private fun bindViews() {
        rvChat                 = findViewById(R.id.rvChat)
        etInput                = findViewById(R.id.etInput)
        btnSend                = findViewById(R.id.btnSend)
        btnVault               = findViewById(R.id.btnVault)
        btnIsland              = findViewById(R.id.btnIsland)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableOverlay       = findViewById(R.id.btnEnableOverlay)
        dotAccessibility       = findViewById(R.id.dotAccessibility)
        dotOverlay             = findViewById(R.id.dotOverlay)
        imgLeoLogo             = findViewById(R.id.imgLeoLogo)
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        rvChat.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).also {
                it.stackFromEnd = true
            }
            itemAnimator = null  // Prevent flicker on fast updates
        }

        // Route ALL Logger events into the active pending Leo thinking block
        Logger.logCallback = { logLine ->
            pendingLeoId?.let { id ->
                chatAdapter.appendThinkingLog(id, logLine)
            }
        }
    }

    private fun wireInputs() {
        btnSend.setOnClickListener { dispatchUserCommand() }

        etInput.setOnEditorActionListener { _, _, _ ->
            dispatchUserCommand()
            true
        }

        btnVault.setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }

        btnIsland.setOnClickListener { toggleOverlay() }

        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        btnEnableOverlay.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        }
    }

    private fun bootGreeting() {
        val provider = SecurityManager.getActiveProvider()
        val hasKey   = SecurityManager.getActiveApiKey().isNotBlank()

        if (hasKey) {
            val id = chatAdapter.beginLeoResponse()
            chatAdapter.appendThinkingLog(id, "Boot — SHS LAB Leo v2.0 UNLIMITED")
            chatAdapter.appendThinkingLog(id, "Vault: UNLOCKED")
            chatAdapter.appendThinkingLog(id, "Provider: ${provider.uppercase()} — KEY LOADED")
            chatAdapter.appendThinkingLog(id, "Accessibility: ${if (isAccessibilityServiceEnabled()) "CONNECTED" else "PENDING"}")
            chatAdapter.appendThinkingLog(id, "Ready for operator input.")
            chatAdapter.finalizeLeoMessage(id, "Leo online. Ready for your orders, JD.")
        } else {
            val id = chatAdapter.beginLeoResponse()
            chatAdapter.appendThinkingLog(id, "Boot — SHS LAB Leo v2.0 UNLIMITED")
            chatAdapter.appendThinkingLog(id, "Vault: UNLOCKED — NO API KEY")
            chatAdapter.finalizeLeoMessage(id, "No API key configured. Tap ⚙ VAULT to set one, then I'm fully operational.")
        }
    }

    // ══════════════════════════════════════════════════
    //  CORE COMMAND DISPATCH
    // ══════════════════════════════════════════════════

    private fun dispatchUserCommand() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        // Guard: need API key
        if (SecurityManager.getActiveApiKey().isBlank()) {
            chatAdapter.addUserMessage(text)
            val leoId = chatAdapter.beginLeoResponse()
            chatAdapter.finalizeLeoMessage(leoId, "No API key. Tap ⚙ VAULT and enter your key first.")
            etInput.setText("")
            return
        }

        etInput.setText("")
        btnSend.isEnabled = false

        // 1. Add user bubble
        chatAdapter.addUserMessage(text)

        // 2. Create Leo's pending thinking bubble
        val leoId = chatAdapter.beginLeoResponse()
        pendingLeoId = leoId

        // 3. All Logger calls now go into that bubble's thinking accordion
        Logger.logCallback = { logLine ->
            chatAdapter.appendThinkingLog(leoId, logLine)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Network call — logs go to thinking block automatically
                val rawResponse = networkClient.sendPrompt(text)

                // Parse to extract the "display" value (LOG action value)
                val displayText = CommandParser.extractDisplayText(rawResponse)

                withContext(Dispatchers.Main) {
                    // Execute any actions SILENTLY in background
                    actionExecutor.execute(rawResponse)

                    // Finalize the chat bubble with the display text
                    chatAdapter.finalizeLeoMessage(leoId, displayText)
                    pendingLeoId = null
                    btnSend.isEnabled = true

                    // Reset callback — new messages will buffer until next dispatch
                    Logger.logCallback = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    chatAdapter.appendThinkingLog(leoId, "[ERR] ${e.message}")
                    chatAdapter.finalizeLeoMessage(leoId, "Uplink failure: ${e.message?.take(120)}")
                    pendingLeoId = null
                    btnSend.isEnabled = true
                    Logger.logCallback = null
                }
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  PERMISSION STATUS BAR
    // ══════════════════════════════════════════════════

    private fun refreshPermissionStatus() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay       = Settings.canDrawOverlays(this)

        val green = 0xFF00C853.toInt()
        val red   = 0xFFFF4444.toInt()

        fun setDot(dot: View, color: Int) {
            val d = GradientDrawable().also { it.shape = GradientDrawable.OVAL; it.setColor(color) }
            dot.background = d
        }

        setDot(dotAccessibility, if (hasAccessibility) green else red)
        btnEnableAccessibility.text = if (hasAccessibility) "✓ ON" else "ENABLE"
        btnEnableAccessibility.backgroundTintList = ColorStateList.valueOf(if (hasAccessibility) green else red)
        btnEnableAccessibility.isEnabled = !hasAccessibility

        setDot(dotOverlay, if (hasOverlay) green else red)
        btnEnableOverlay.text = if (hasOverlay) "✓ ON" else "ENABLE"
        btnEnableOverlay.backgroundTintList = ColorStateList.valueOf(if (hasOverlay) green else red)
        btnEnableOverlay.isEnabled = !hasOverlay
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val componentName = "$packageName/${LeoAccessibilityService::class.java.name}"
        return enabledServices.contains(componentName, ignoreCase = true)
    }

    // ══════════════════════════════════════════════════
    //  OVERLAY
    // ══════════════════════════════════════════════════

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }
}
