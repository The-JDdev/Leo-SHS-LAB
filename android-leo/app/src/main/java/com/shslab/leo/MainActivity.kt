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
import com.shslab.leo.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════
 *  LEO MAIN ACTIVITY — SHS LAB v3
 *  Level 5 Autonomy — Agentic Mission Loop
 *
 *  New in v3:
 *  • Agentic loop: AI ↔ Executor ↔ Mission Report ↔ AI
 *    until AI issues LOG (mission complete)
 *  • READ_SCREEN result is injected into the next AI payload
 *  • WAIT_FOR result feeds back into AI for adaptive decisions
 *  • Max 20 agentic steps with graceful summary fallback
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

    @Volatile private var pendingLeoId: String? = null

    /** Max agentic loop steps before forcing a summary */
    private val MAX_AGENTIC_STEPS = 20

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
            adapter       = chatAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            itemAnimator  = null
        }

        Logger.logCallback = { logLine ->
            pendingLeoId?.let { id -> chatAdapter.appendThinkingLog(id, logLine) }
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

        val leoId = chatAdapter.beginLeoResponse()
        pendingLeoId = leoId
        chatAdapter.appendThinkingLog(leoId, "SHS LAB Leo v3.0 — LEVEL 5 AUTONOMY")
        chatAdapter.appendThinkingLog(leoId, "Agentic Mission Engine: ONLINE")

        if (hasKey) {
            chatAdapter.appendThinkingLog(leoId, "Vault: UNLOCKED — KEY LOADED")
            chatAdapter.appendThinkingLog(leoId, "Provider: ${provider.uppercase()}")
            chatAdapter.appendThinkingLog(leoId, "Accessibility: ${if (isAccessibilityServiceEnabled()) "CONNECTED" else "PENDING"}")
            chatAdapter.appendThinkingLog(leoId, "WAIT_FOR engine: ARMED")
            chatAdapter.appendThinkingLog(leoId, "READ_SCREEN engine: ARMED")
            chatAdapter.appendThinkingLog(leoId, "Mission reporting loop: ARMED")
            chatAdapter.finalizeLeoMessage(leoId, "Leo online. Level 5 Autonomy active — I can now see the screen, wait for elements, and report every mission step. Give me an order, JD.")
        } else {
            chatAdapter.appendThinkingLog(leoId, "Vault: UNLOCKED — NO API KEY")
            chatAdapter.finalizeLeoMessage(leoId, "No API key configured. Tap ⚙ VAULT to add one, then I'm fully operational at Level 5.")
        }
        pendingLeoId = null
    }

    // ══════════════════════════════════════════════════
    //  CORE COMMAND DISPATCH — LEVEL 5 AGENTIC LOOP
    // ══════════════════════════════════════════════════

    private fun dispatchUserCommand() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        if (SecurityManager.getActiveApiKey().isBlank()) {
            chatAdapter.addUserMessage(text)
            val id = chatAdapter.beginLeoResponse()
            chatAdapter.finalizeLeoMessage(id, "No API key. Tap ⚙ VAULT and enter your key first.")
            etInput.setText("")
            return
        }

        etInput.setText("")
        btnSend.isEnabled = false

        // 1. Add JD's message bubble
        chatAdapter.addUserMessage(text)

        // 2. Open Leo's thinking bubble — stays open during the entire mission
        val leoId = chatAdapter.beginLeoResponse()
        pendingLeoId = leoId

        // 3. Route all Logger output into that bubble
        Logger.logCallback = { logLine ->
            chatAdapter.appendThinkingLog(leoId, logLine)
        }

        // 4. Launch the agentic loop on IO thread
        lifecycleScope.launch(Dispatchers.IO) {
            runAgenticLoop(text, leoId)
        }
    }

    /**
     * THE LEVEL 5 AGENTIC LOOP
     *
     * Flow:
     *   JD command → AI → action → mission report → AI → action → ... → LOG → show to JD
     *
     * The loop continues until:
     *   a) AI sends a LOG action (mission done)
     *   b) MAX_AGENTIC_STEPS is reached (force summary)
     *   c) An unrecoverable exception occurs
     *
     * READ_SCREEN results are automatically injected into the next AI call so Leo
     * can see and react to what's on screen. WAIT_FOR results tell AI whether the
     * element appeared (proceed) or timed out (adapt).
     */
    private suspend fun runAgenticLoop(initialInput: String, leoId: String) {
        var currentInput = initialInput
        var step = 0

        try {
            while (step < MAX_AGENTIC_STEPS) {
                step++
                chatAdapter.appendThinkingLog(leoId, "[MISSION STEP $step/$MAX_AGENTIC_STEPS]")

                // ── Call AI ──────────────────────────────────────────
                val rawResponse = networkClient.sendPrompt(currentInput)
                chatAdapter.appendThinkingLog(leoId, "[AI] → ${rawResponse.take(80)}")

                // ── Execute the action ────────────────────────────────
                val result = actionExecutor.executeAndReport(rawResponse)

                // ── LOG action = mission complete → show final message ─
                if (result.isLogAction) {
                    chatAdapter.appendThinkingLog(leoId, "[MISSION COMPLETE] in $step step${if (step > 1) "s" else ""}")
                    withContext(Dispatchers.Main) {
                        chatAdapter.finalizeLeoMessage(
                            leoId,
                            result.displayText ?: "Mission complete."
                        )
                        finishDispatch()
                    }
                    return
                }

                // ── Non-LOG: build mission report for next AI call ────
                val nextInput = buildMissionReport(step, result.report)
                chatAdapter.appendThinkingLog(leoId, "[REPORT] → ${result.report.take(100)}")
                currentInput = nextInput
            }

            // ── Max steps reached: force AI to summarize ─────────────
            chatAdapter.appendThinkingLog(leoId, "[MAX STEPS] Forcing summary...")
            val forcedRaw = networkClient.sendPrompt(
                "[SYSTEM: You have executed $MAX_AGENTIC_STEPS steps. " +
                "Summarize what you accomplished for JD using a LOG action NOW. " +
                "Be specific about what worked and what didn't.]"
            )
            val forcedResult = actionExecutor.executeAndReport(forcedRaw)
            withContext(Dispatchers.Main) {
                chatAdapter.finalizeLeoMessage(
                    leoId,
                    forcedResult.displayText ?: "Mission executed across $MAX_AGENTIC_STEPS steps."
                )
                finishDispatch()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                chatAdapter.appendThinkingLog(leoId, "[ERR] ${e.message}")
                chatAdapter.finalizeLeoMessage(
                    leoId,
                    "Uplink failure on step $step: ${e.message?.take(120)}"
                )
                finishDispatch()
            }
        }
    }

    /**
     * Build the feedback message Leo sends back to AI after each action.
     * The AI reads this and decides the next step autonomously.
     */
    private fun buildMissionReport(step: Int, report: String): String {
        return buildString {
            appendLine("[SYSTEM MISSION REPORT — Step $step]")
            appendLine("Execution results:")
            appendLine(report)
            appendLine()
            appendLine("Instructions: Continue with the next atomic action to complete JD's request.")
            appendLine("- If you need to see what's on screen, use READ_SCREEN.")
            appendLine("- If you need to wait for a UI element, use UI_CONTROL with sub_action WAIT_FOR.")
            appendLine("- When the mission is fully complete, use LOG to give JD a natural conversational summary.")
            appendLine("- If something failed, adapt — try a different approach. Never give up without trying.")
        }
    }

    private fun finishDispatch() {
        pendingLeoId = null
        btnSend.isEnabled = true
        Logger.logCallback = null
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
