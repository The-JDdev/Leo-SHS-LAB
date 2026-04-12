package com.shslab.leo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shslab.leo.accessibility.LeoAccessibilityService
import com.shslab.leo.browser.LeoBrowserEngine
import com.shslab.leo.chat.ChatAdapter
import com.shslab.leo.core.LeoProtocol
import com.shslab.leo.core.Logger
import com.shslab.leo.executor.ActionExecutor
import com.shslab.leo.network.LeoNetworkClient
import com.shslab.leo.overlay.OverlayService
import com.shslab.leo.parser.CommandParser
import com.shslab.leo.security.SecurityManager
import com.shslab.leo.voice.SpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════
 *  LEO MAIN ACTIVITY — APEX ARCHITECTURE v5
 *  SHS LAB — REACT INTELLIGENCE ENGINE
 *
 *  ReAct Loop:
 *    User message →
 *    AI: one JSON action →
 *    Execute →
 *    Auto READ_SCREEN →
 *    Feedback to AI →
 *    AI: next action →
 *    ... until MISSION_COMPLETE
 *
 *  Dual Engine: AccessibilityService + InbuiltBrowser
 *  Voice: STT mic input + TTS output
 *  Theme: Dark/Light from SecurityManager
 * ══════════════════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

    // ── Views ──
    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnMic: ImageButton
    private lateinit var btnVault: Button
    private lateinit var btnSurveillance: Button
    private lateinit var btnIsland: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnEnableOverlay: Button
    private lateinit var dotAccessibility: View
    private lateinit var dotOverlay: View
    private lateinit var imgLeoLogo: ImageView
    private lateinit var tvAgentName: TextView

    private lateinit var chatAdapter: ChatAdapter

    private val networkClient  by lazy { LeoNetworkClient() }
    private val actionExecutor by lazy { ActionExecutor(this) }
    private val speechManager  by lazy { SpeechManager(this) }
    private val browserEngine  by lazy { LeoBrowserEngine.getInstance(this) }

    @Volatile private var pendingLeoId: String? = null
    @Volatile private var isListening  = false

    private val MAX_REACT_STEPS = 30

    companion object {
        private const val REQUEST_MIC_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        SecurityManager.init(this)
        setContentView(R.layout.activity_main)
        bindViews()
        setupChat()
        wireInputs()
        bootGreeting()

        // Pre-init browser engine on main thread
        browserEngine
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        updateAgentNameDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.clearCallbacks()
        speechManager.destroy()
    }

    private fun applyTheme() {
        val darkMode = SecurityManager.isDarkMode()
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun bindViews() {
        rvChat                 = findViewById(R.id.rvChat)
        etInput                = findViewById(R.id.etInput)
        btnSend                = findViewById(R.id.btnSend)
        btnMic                 = findViewById(R.id.btnMic)
        btnVault               = findViewById(R.id.btnVault)
        btnSurveillance        = findViewById(R.id.btnSurveillance)
        btnIsland              = findViewById(R.id.btnIsland)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableOverlay       = findViewById(R.id.btnEnableOverlay)
        dotAccessibility       = findViewById(R.id.dotAccessibility)
        dotOverlay             = findViewById(R.id.dotOverlay)
        imgLeoLogo             = findViewById(R.id.imgLeoLogo)
        tvAgentName            = findViewById(R.id.tvAgentName)
    }

    private fun updateAgentNameDisplay() {
        val name = SecurityManager.getAgentName()
        tvAgentName.text = name
        supportActionBar?.title = name
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        rvChat.apply {
            adapter       = chatAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            itemAnimator  = null
        }
    }

    private fun wireInputs() {
        btnSend.setOnClickListener { dispatchUserCommand() }
        etInput.setOnEditorActionListener { _, _, _ -> dispatchUserCommand(); true }

        btnMic.setOnClickListener { toggleVoiceInput() }

        btnVault.setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }

        btnSurveillance.setOnClickListener {
            startActivity(Intent(this, SurveillanceActivity::class.java))
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

    // ══════════════════════════════════════════════════
    //  VOICE INPUT — STT
    // ══════════════════════════════════════════════════

    private fun toggleVoiceInput() {
        if (isListening) {
            speechManager.stopListening()
            isListening = false
            btnMic.setColorFilter(Color.WHITE)
            return
        }

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
            return
        }

        isListening = true
        btnMic.setColorFilter(Color.parseColor("#E91E8C"))

        speechManager.startListening(
            onResult = { text ->
                runOnUiThread {
                    isListening = false
                    btnMic.setColorFilter(Color.WHITE)
                    etInput.setText(text)
                    dispatchUserCommand()
                }
            },
            onError = { err ->
                runOnUiThread {
                    isListening = false
                    btnMic.setColorFilter(Color.WHITE)
                    Logger.warn("STT error: $err")
                }
            },
            onPartialResult = { partial ->
                runOnUiThread { etInput.setText(partial) }
            }
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleVoiceInput()
        }
    }

    // ══════════════════════════════════════════════════
    //  BOOT GREETING
    // ══════════════════════════════════════════════════

    private fun bootGreeting() {
        val agentName = SecurityManager.getAgentName()
        updateAgentNameDisplay()
        val provider = SecurityManager.getActiveProvider()
        val hasKey   = SecurityManager.getActiveApiKey().isNotBlank()
        val leoId    = chatAdapter.beginLeoResponse()
        pendingLeoId = leoId

        chatAdapter.appendThinkingLog(leoId, "SHS LAB $agentName v5.0 — APEX ARCHITECTURE")
        chatAdapter.appendThinkingLog(leoId, "ReAct Intelligence Engine: ONLINE")
        chatAdapter.appendThinkingLog(leoId, "Engine: AccessibilityService + InbuiltBrowser + Terminal")
        chatAdapter.appendThinkingLog(leoId, "Voice: STT + TTS")
        chatAdapter.appendThinkingLog(leoId, "Surveillance: LIVE")

        if (hasKey) {
            chatAdapter.appendThinkingLog(leoId, "Vault: UNLOCKED — Provider: ${provider.uppercase()}")
            chatAdapter.appendThinkingLog(leoId, "Accessibility: ${if (isAccessibilityServiceEnabled()) "CONNECTED" else "PENDING"}")
            val greeting = "I'm $agentName, JD. Your omnipotent digital twin — fully operational. Every app, every file, the web, the terminal. I execute one step at a time, reading the screen between each action. Give me any mission."
            chatAdapter.finalizeLeoMessage(leoId, greeting)
            if (SecurityManager.isTTSEnabled()) speechManager.speak(greeting)
        } else {
            chatAdapter.appendThinkingLog(leoId, "Vault: LOCKED — No API key")
            chatAdapter.finalizeLeoMessage(leoId, "No API key set, JD. Tap VAULT, enter your key, and I'll be fully operational.")
        }

        pendingLeoId = null
    }

    // ══════════════════════════════════════════════════
    //  COMMAND DISPATCH
    // ══════════════════════════════════════════════════

    private fun dispatchUserCommand() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        if (SecurityManager.getActiveApiKey().isBlank()) {
            chatAdapter.addUserMessage(text)
            val id = chatAdapter.beginLeoResponse()
            chatAdapter.finalizeLeoMessage(id, "No API key, JD. Tap VAULT first.")
            etInput.setText("")
            return
        }

        etInput.setText("")
        btnSend.isEnabled = false
        btnMic.isEnabled  = false
        chatAdapter.addUserMessage(text)

        val leoId = chatAdapter.beginLeoResponse()
        pendingLeoId = leoId

        Logger.logCallback = { line -> chatAdapter.appendThinkingLog(leoId, line) }

        lifecycleScope.launch(Dispatchers.IO) {
            runReActLoop(text, leoId)
        }
    }

    // ══════════════════════════════════════════════════
    //  REACT LOOP — CORE ENGINE
    //
    //  Claude Code / Gemini CLI style:
    //   AI → ONE action → Execute → READ_SCREEN → Feedback → AI → ...
    //   Until MISSION_COMPLETE
    // ══════════════════════════════════════════════════

    private suspend fun runReActLoop(initialMission: String, leoId: String) {
        val agentName  = SecurityManager.getAgentName()
        var stepCount  = 0

        // Build conversation starting with system prompt + user mission
        val conversation = mutableListOf<Map<String, String>>()
        conversation.add(mapOf("role" to "system", "content" to LeoProtocol.SYSTEM_IDENTITY.trimIndent()))
        conversation.add(mapOf("role" to "user", "content" to initialMission))

        try {
            while (stepCount < MAX_REACT_STEPS) {
                stepCount++
                chatAdapter.appendThinkingLog(leoId, "── ReAct Step $stepCount/$MAX_REACT_STEPS ──")

                // ── AI Call ──────────────────────────────────────
                val aiRaw = networkClient.sendWithHistory(conversation)
                chatAdapter.appendThinkingLog(leoId, "[AI→] ${aiRaw.take(100)}")

                // Add AI response to conversation
                conversation.add(mapOf("role" to "assistant", "content" to aiRaw))

                // ── Parse single command ─────────────────────────
                val cmd = CommandParser.parseSingle(aiRaw)

                // ── MISSION_COMPLETE? ────────────────────────────
                if (cmd.action == LeoProtocol.Action.MISSION_COMPLETE ||
                    cmd.action == LeoProtocol.Action.LOG) {
                    val message = cmd.value.ifBlank {
                        cmd.raw.optString("message", "Mission complete, $agentName here — all done JD.")
                    }
                    chatAdapter.appendThinkingLog(leoId, "✓ Mission complete after $stepCount step(s)")
                    withContext(Dispatchers.Main) {
                        chatAdapter.finalizeLeoMessage(leoId, message)
                        if (SecurityManager.isTTSEnabled()) speechManager.speak(message)
                        finishDispatch()
                    }
                    return
                }

                // ── Execute the single action ────────────────────
                val stepResult = actionExecutor.executeSingle(cmd)
                val resultStr  = stepResult.result
                chatAdapter.appendThinkingLog(leoId,
                    "${if (stepResult.isError) "✗" else "✓"} [${cmd.action}:${cmd.subAction}] $resultStr"
                )

                // ── Auto READ_SCREEN after UI/browser actions ────
                val screenState = if (shouldReadScreen(cmd.action)) {
                    val svc = LeoAccessibilityService.instance
                    if (svc != null) {
                        Thread.sleep(600) // give UI time to settle
                        svc.readScreenText().also {
                            chatAdapter.appendThinkingLog(leoId, "[EYES] ${it.lines().size} UI nodes")
                        }
                    } else "accessibility_not_connected:enable_in_settings"
                } else {
                    "no_screen_change:${cmd.action}"
                }

                // ── Build feedback for AI ────────────────────────
                val feedback = if (stepResult.isError) {
                    LeoProtocol.buildErrorFeedback(
                        action = "${cmd.action}:${cmd.subAction} target='${cmd.target}'",
                        error = resultStr,
                        screenState = screenState,
                        stepNumber = stepCount
                    )
                } else {
                    LeoProtocol.buildStepFeedback(
                        action = "${cmd.action}:${cmd.subAction} target='${cmd.target}'",
                        executionResult = resultStr,
                        screenState = screenState,
                        stepNumber = stepCount
                    )
                }

                // Add feedback to conversation (as user turn — system feedback)
                conversation.add(mapOf("role" to "user", "content" to feedback))
            }

            // Hit max steps — force final summary
            chatAdapter.appendThinkingLog(leoId, "[MAX STEPS] Requesting final summary...")
            conversation.add(mapOf("role" to "user", "content" to
                "[SYSTEM: You have used $MAX_REACT_STEPS ReAct steps. " +
                "Output MISSION_COMPLETE now with a detailed, warm summary of everything you did for JD.]"
            ))
            val finalRaw = networkClient.sendWithHistory(conversation)
            val finalCmd = CommandParser.parseSingle(finalRaw)
            val finalMsg = finalCmd.value.ifBlank {
                finalCmd.raw.optString("message", "Completed $MAX_REACT_STEPS steps, JD.")
            }
            withContext(Dispatchers.Main) {
                chatAdapter.finalizeLeoMessage(leoId, finalMsg)
                if (SecurityManager.isTTSEnabled()) speechManager.speak(finalMsg)
                finishDispatch()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                chatAdapter.appendThinkingLog(leoId, "[ERR] ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                chatAdapter.finalizeLeoMessage(leoId, "Connection error on step $stepCount, JD: ${e.message?.take(100)}")
                finishDispatch()
            }
        }
    }

    /**
     * Determines whether we should auto-read the screen after this action.
     * UI control and browser navigation change the screen; hardware/file/shell don't.
     */
    private fun shouldReadScreen(action: String): Boolean = when (action) {
        LeoProtocol.Action.UI_CONTROL,
        LeoProtocol.Action.BROWSER_NAVIGATE,
        LeoProtocol.Action.BROWSER_CLICK,
        LeoProtocol.Action.UI_CLICK,
        LeoProtocol.Action.WAIT_FOR      -> true
        else                              -> false
    }

    private fun finishDispatch() {
        pendingLeoId       = null
        btnSend.isEnabled  = true
        btnMic.isEnabled   = true
        Logger.logCallback = null
    }

    // ══════════════════════════════════════════════════
    //  PERMISSION STATUS
    // ══════════════════════════════════════════════════

    private fun refreshPermissionStatus() {
        val hasA  = isAccessibilityServiceEnabled()
        val hasO  = Settings.canDrawOverlays(this)
        val green = 0xFF00C853.toInt()
        val red   = 0xFFFF4444.toInt()

        fun dot(v: View, c: Int) {
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        }

        dot(dotAccessibility, if (hasA) green else red)
        btnEnableAccessibility.text              = if (hasA) "✓ ON" else "ENABLE"
        btnEnableAccessibility.backgroundTintList = ColorStateList.valueOf(if (hasA) green else red)
        btnEnableAccessibility.isEnabled          = !hasA

        dot(dotOverlay, if (hasO) green else red)
        btnEnableOverlay.text              = if (hasO) "✓ ON" else "ENABLE"
        btnEnableOverlay.backgroundTintList = ColorStateList.valueOf(if (hasO) green else red)
        btnEnableOverlay.isEnabled          = !hasO
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
