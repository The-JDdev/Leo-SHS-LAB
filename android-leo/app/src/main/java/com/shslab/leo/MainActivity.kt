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
 *  LEO MAIN ACTIVITY — SHS LAB v4
 *  ABSOLUTE SOVEREIGNTY — OMNIPOTENT SURROGATE
 *
 *  Agentic loop: AI responds with a JSON array
 *  of sequential commands. Leo executes them all,
 *  compiles a mission report, and feeds it back
 *  to AI until a LOG action signals mission done.
 * ══════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

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

    private lateinit var chatAdapter: ChatAdapter

    private val networkClient by lazy { LeoNetworkClient() }
    private val actionExecutor by lazy { ActionExecutor(this) }

    @Volatile private var pendingLeoId: String? = null

    /** Max agentic loop iterations before forcing final summary */
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
        etInput.setOnEditorActionListener { _, _, _ -> dispatchUserCommand(); true }
        btnVault.setOnClickListener { startActivity(Intent(this, VaultActivity::class.java)) }
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
        val leoId    = chatAdapter.beginLeoResponse()
        pendingLeoId = leoId

        chatAdapter.appendThinkingLog(leoId, "SHS LAB Leo v4.0 — ABSOLUTE SOVEREIGNTY")
        chatAdapter.appendThinkingLog(leoId, "Multi-command array engine: ONLINE")
        chatAdapter.appendThinkingLog(leoId, "Sub_actions: OPEN_APP, CLICK, LONG_PRESS, TYPE, SCROLL, WAIT_FOR, BACK, HOME")
        chatAdapter.appendThinkingLog(leoId, "Hardware: flashlight, vibrate, battery, volume, brightness, wifi, camera")

        if (hasKey) {
            chatAdapter.appendThinkingLog(leoId, "Vault: UNLOCKED — Provider: ${provider.uppercase()}")
            chatAdapter.appendThinkingLog(leoId, "Accessibility: ${if (isAccessibilityServiceEnabled()) "CONNECTED" else "PENDING — enable in Settings"}")
            chatAdapter.finalizeLeoMessage(leoId, "Leo online — Absolute Sovereignty active. I am your digital twin, JD. Give me any order and I will execute it completely.")
        } else {
            chatAdapter.appendThinkingLog(leoId, "Vault: UNLOCKED — NO API KEY — tap ⚙ VAULT")
            chatAdapter.finalizeLeoMessage(leoId, "No API key set. Tap ⚙ VAULT, enter your key, save. Then I'm fully operational.")
        }
        pendingLeoId = null
    }

    // ══════════════════════════════════════════════════
    //  CORE COMMAND DISPATCH + AGENTIC LOOP
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
        chatAdapter.addUserMessage(text)

        val leoId = chatAdapter.beginLeoResponse()
        pendingLeoId = leoId

        Logger.logCallback = { logLine ->
            chatAdapter.appendThinkingLog(leoId, logLine)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            runAgenticLoop(text, leoId)
        }
    }

    /**
     * ABSOLUTE SOVEREIGNTY AGENTIC LOOP
     *
     * Each iteration:
     *   1. Send currentInput to AI
     *   2. AI responds with JSON array of sequential commands
     *   3. Executor runs ALL commands in the array
     *   4. If a LOG appears in the array → mission done → show to JD
     *   5. Otherwise → compile mission report → feed back to AI → repeat
     *
     * READ_SCREEN results from the array are automatically included
     * in the mission report so AI can see what was on screen.
     *
     * Max 20 loops with graceful forced-summary fallback.
     */
    private suspend fun runAgenticLoop(initialInput: String, leoId: String) {
        var currentInput = initialInput
        var loopCount    = 0

        try {
            while (loopCount < MAX_AGENTIC_STEPS) {
                loopCount++
                chatAdapter.appendThinkingLog(leoId, "── MISSION LOOP $loopCount/$MAX_AGENTIC_STEPS ──")

                // Call AI
                val rawResponse = networkClient.sendPrompt(currentInput)
                chatAdapter.appendThinkingLog(leoId, "[AI] ${rawResponse.take(80)}...")

                // Execute the full command array
                val result = actionExecutor.executeAndReport(rawResponse)

                if (result.isLogAction) {
                    // LOG found in array — mission complete
                    chatAdapter.appendThinkingLog(leoId, "✓ Mission complete in $loopCount loop${if (loopCount > 1) "s" else ""}")
                    withContext(Dispatchers.Main) {
                        chatAdapter.finalizeLeoMessage(leoId, result.displayText ?: "Mission complete, JD.")
                        finishDispatch()
                    }
                    return
                }

                // No LOG yet — build feedback for next AI call
                currentInput = buildFeedbackMessage(loopCount, result.report)
                chatAdapter.appendThinkingLog(leoId, "[REPORT] ${result.report.take(120)}")
            }

            // Hit MAX_AGENTIC_STEPS — force AI to wrap up
            chatAdapter.appendThinkingLog(leoId, "[MAX LOOPS] Forcing final summary...")
            val forcePrompt = "[SYSTEM: You have executed $MAX_AGENTIC_STEPS mission loops on this Android device. " +
                "Give JD a complete natural-language summary of everything you did and what the final status is. " +
                "Use a single LOG action now. Be specific and warm.]"
            val finalRaw    = networkClient.sendPrompt(forcePrompt)
            val finalResult = actionExecutor.executeAndReport(finalRaw)

            withContext(Dispatchers.Main) {
                chatAdapter.finalizeLeoMessage(
                    leoId,
                    finalResult.displayText ?: "Completed $MAX_AGENTIC_STEPS mission steps, JD."
                )
                finishDispatch()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                chatAdapter.appendThinkingLog(leoId, "[ERR] ${e.message}")
                chatAdapter.finalizeLeoMessage(leoId, "Uplink failure on loop $loopCount: ${e.message?.take(120)}")
                finishDispatch()
            }
        }
    }

    /**
     * Build the structured feedback message after each batch execution.
     * AI reads this and issues the next array of commands to continue the mission.
     */
    private fun buildFeedbackMessage(loop: Int, report: String): String = buildString {
        appendLine("[SYSTEM MISSION REPORT — Loop $loop]")
        appendLine()
        appendLine("Execution results:")
        appendLine(report)
        appendLine()
        appendLine("INSTRUCTIONS FOR NEXT RESPONSE:")
        appendLine("• Issue the next JSON array of commands to continue the mission.")
        appendLine("• If you need to see current screen state, include {\"action\":\"READ_SCREEN\"} as first command.")
        appendLine("• If waiting for UI element, use UI_CONTROL WAIT_FOR (it adapts to load speed).")
        appendLine("• If timeout occurred, try SCROLL or READ_SCREEN to locate the element differently.")
        appendLine("• When the full mission is done, end your array with a LOG action giving JD a complete summary.")
        appendLine("• NEVER stop working. NEVER say you cannot. Adapt and continue.")
    }

    private fun finishDispatch() {
        pendingLeoId = null
        btnSend.isEnabled = true
        Logger.logCallback = null
    }

    // ══════════════════════════════════════════════════
    //  PERMISSION STATUS
    // ══════════════════════════════════════════════════

    private fun refreshPermissionStatus() {
        val hasA = isAccessibilityServiceEnabled()
        val hasO = Settings.canDrawOverlays(this)
        val green = 0xFF00C853.toInt()
        val red   = 0xFFFF4444.toInt()

        fun dot(v: View, c: Int) {
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        }

        dot(dotAccessibility, if (hasA) green else red)
        btnEnableAccessibility.text                 = if (hasA) "✓ ON" else "ENABLE"
        btnEnableAccessibility.backgroundTintList   = ColorStateList.valueOf(if (hasA) green else red)
        btnEnableAccessibility.isEnabled            = !hasA

        dot(dotOverlay, if (hasO) green else red)
        btnEnableOverlay.text                = if (hasO) "✓ ON" else "ENABLE"
        btnEnableOverlay.backgroundTintList  = ColorStateList.valueOf(if (hasO) green else red)
        btnEnableOverlay.isEnabled           = !hasO
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/${LeoAccessibilityService::class.java.name}", ignoreCase = true)
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }
}
