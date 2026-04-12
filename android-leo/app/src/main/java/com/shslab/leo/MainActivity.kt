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
import com.shslab.leo.executor.ActionDispatcher
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
 *  LEO MAIN ACTIVITY — SUPREME SOVEREIGN v6
 *  SHS LAB — TERMINAL-FIRST REACT ENGINE
 *
 *  Supreme Sovereign ReAct Loop:
 *  1. AI → ONE JSON action
 *  2. ActionDispatcher.intercept() — No-Excuse check
 *     → If excuse: silently reinject NO_EXCUSE, loop again
 *     → If lazy MISSION_COMPLETE: demand verification
 *  3. Execute action
 *  4. Auto READ_SCREEN on UI actions
 *  5. Append PERSISTENT_REMINDER to EVERY feedback
 *  6. Endurance boost every 10 steps
 *  7. MISSION_COMPLETE only when verified
 *
 *  MAX_REACT_STEPS = 50 (was 30)
 *  Terminal-First: SHELL_EXEC for files/git/GitHub
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

    // Supreme Sovereign: 50 steps max (was 30)
    private val MAX_REACT_STEPS = 50
    // Endurance boost injection every N steps
    private val ENDURANCE_BOOST_INTERVAL = 10
    // Max silent reinjects before alerting JD
    private val MAX_CONSECUTIVE_EXCUSES = 6

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
        btnVault.setOnClickListener { startActivity(Intent(this, VaultActivity::class.java)) }
        btnSurveillance.setOnClickListener { startActivity(Intent(this, SurveillanceActivity::class.java)) }
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

        chatAdapter.appendThinkingLog(leoId, "SHS LAB $agentName v6.0 — SUPREME SOVEREIGN")
        chatAdapter.appendThinkingLog(leoId, "Terminal-First ReAct Engine: ONLINE")
        chatAdapter.appendThinkingLog(leoId, "Engines: Shell + AccessibilityService + Browser")
        chatAdapter.appendThinkingLog(leoId, "Anti-Laziness Interceptor: ARMED")
        chatAdapter.appendThinkingLog(leoId, "Voice: STT + TTS | Surveillance: LIVE")

        if (hasKey) {
            chatAdapter.appendThinkingLog(leoId, "Vault: UNLOCKED — Provider: ${provider.uppercase()}")
            chatAdapter.appendThinkingLog(leoId, "Accessibility: ${if (isAccessibilityServiceEnabled()) "CONNECTED" else "PENDING"}")
            val greeting = "Supreme Sovereign online, JD. I'm $agentName — your omnipotent digital twin. Terminal-first execution, anti-laziness guard, infinite endurance. I execute one verified step at a time. Give me any mission."
            chatAdapter.finalizeLeoMessage(leoId, greeting)
            if (SecurityManager.isTTSEnabled()) speechManager.speak(greeting)
        } else {
            chatAdapter.appendThinkingLog(leoId, "Vault: LOCKED — No API key")
            chatAdapter.finalizeLeoMessage(leoId, "No API key set, JD. Tap VAULT, enter your key, and Supreme Sovereign goes live.")
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
    //  SUPREME SOVEREIGN REACT LOOP — v6 ENGINE
    //
    //  Enhancements over v5:
    //  - ActionDispatcher.intercept() on EVERY AI response
    //  - Silent reinject on excuse (up to MAX_CONSECUTIVE_EXCUSES)
    //  - Demand verification before MISSION_COMPLETE
    //  - Persistent SYSTEM REMINDER in EVERY API payload
    //  - Endurance boost every ENDURANCE_BOOST_INTERVAL steps
    //  - MAX_REACT_STEPS = 50
    // ══════════════════════════════════════════════════

    private suspend fun runReActLoop(initialMission: String, leoId: String) {
        val agentName    = SecurityManager.getAgentName()
        var stepCount    = 0
        var totalRejects = 0

        // Mission state for ActionDispatcher
        val missionState = ActionDispatcher.MissionState()

        // Build conversation: system + user mission
        val conversation = mutableListOf<Map<String, String>>()
        conversation.add(mapOf("role" to "system", "content" to LeoProtocol.SYSTEM_IDENTITY.trimIndent()))
        conversation.add(mapOf("role" to "user", "content" to initialMission))

        try {
            while (stepCount < MAX_REACT_STEPS) {
                stepCount++
                chatAdapter.appendThinkingLog(leoId, "── Step $stepCount/$MAX_REACT_STEPS ──")

                // ── ENDURANCE BOOST every N steps ──────────────
                if (stepCount > 1 && stepCount % ENDURANCE_BOOST_INTERVAL == 0) {
                    val boost = ActionDispatcher.buildEnduranceBoost(stepCount)
                    conversation.add(mapOf("role" to "user", "content" to boost))
                    chatAdapter.appendThinkingLog(leoId, "[BOOST] Step $stepCount — Endurance injected")
                }

                // ── AI CALL with PERSISTENT REMINDER ───────────
                // Build payload with reminder appended to final message
                val payload = LeoProtocol.buildReActPayloadWithReminder(
                    conversationHistory = conversation.drop(1), // drop system (already in payload builder)
                    stepNumber = stepCount
                )
                val aiRaw = networkClient.sendWithHistory(payload)
                chatAdapter.appendThinkingLog(leoId, "[AI→] ${aiRaw.take(120)}")

                // ── SUPREME SOVEREIGN INTERCEPT ─────────────────
                val decision = ActionDispatcher.intercept(aiRaw, missionState)

                when (decision) {

                    is ActionDispatcher.DispatchDecision.Reinject -> {
                        totalRejects++
                        chatAdapter.appendThinkingLog(leoId, "[⚠ INTERCEPTOR] Excuse '${decision.reason}' — reinject #$totalRejects")

                        if (totalRejects >= MAX_CONSECUTIVE_EXCUSES) {
                            // Too many excuses — tell JD and abort
                            withContext(Dispatchers.Main) {
                                chatAdapter.finalizeLeoMessage(leoId,
                                    "JD, the AI model kept refusing to execute ($totalRejects consecutive times). " +
                                    "Try a different model in the Vault (e.g. GPT-4o, Claude, Gemini Pro). " +
                                    "Original mission was: $initialMission"
                                )
                                finishDispatch()
                            }
                            return
                        }

                        // Silently inject the override — do NOT add aiRaw to conversation
                        conversation.add(mapOf("role" to "user", "content" to decision.reinjectMessage))
                        continue
                    }

                    is ActionDispatcher.DispatchDecision.DemandVerification -> {
                        chatAdapter.appendThinkingLog(leoId, "[GUARD] Lazy MISSION_COMPLETE blocked — demanding proof: ${decision.missingProof}")
                        // Add the demand as user message so AI must verify
                        conversation.add(mapOf("role" to "assistant", "content" to aiRaw))
                        conversation.add(mapOf("role" to "user", "content" to decision.verifyMessage))
                        continue
                    }

                    is ActionDispatcher.DispatchDecision.Proceed -> {
                        // All clear — proceed with execution
                        val cleanJson = decision.cleanJson
                        // Add to conversation history
                        conversation.add(mapOf("role" to "assistant", "content" to cleanJson))

                        // ── Parse the single action ─────────────
                        val cmd = CommandParser.parseSingle(cleanJson)

                        // ── MISSION_COMPLETE ─────────────────────
                        if (cmd.action == LeoProtocol.Action.MISSION_COMPLETE ||
                            cmd.action == LeoProtocol.Action.LOG) {
                            val message = cmd.value.ifBlank {
                                cmd.raw.optString("message", "Mission complete, $agentName here — done, JD.")
                            }
                            chatAdapter.appendThinkingLog(leoId, "✓ Verified complete after $stepCount step(s)")

                            // Check if this MISSION_COMPLETE is asking for credentials
                            if (ActionDispatcher.isAskingForCredentials(message)) {
                                withContext(Dispatchers.Main) {
                                    chatAdapter.finalizeLeoMessage(leoId, message)
                                    if (SecurityManager.isTTSEnabled()) speechManager.speak(message)
                                    finishDispatch()
                                }
                                return
                            }

                            withContext(Dispatchers.Main) {
                                chatAdapter.finalizeLeoMessage(leoId, message)
                                if (SecurityManager.isTTSEnabled()) speechManager.speak(message)
                                finishDispatch()
                            }
                            return
                        }

                        // ── EXECUTE the action ───────────────────
                        val stepResult = actionExecutor.executeSingle(cmd)
                        val resultStr  = stepResult.result
                        chatAdapter.appendThinkingLog(leoId,
                            "${if (stepResult.isError) "✗" else "✓"} [${cmd.action}] → ${resultStr.take(120)}"
                        )

                        // ── AUTO READ_SCREEN after UI/browser ────
                        val screenState = if (shouldReadScreen(cmd.action)) {
                            val svc = LeoAccessibilityService.instance
                            if (svc != null) {
                                Thread.sleep(700) // let UI settle
                                svc.readScreenText().also {
                                    chatAdapter.appendThinkingLog(leoId, "[EYES] ${it.lines().size} nodes")
                                }
                            } else {
                                "accessibility_not_connected:enable_in_settings"
                            }
                        } else {
                            resultStr.take(500) // For shell/file, screen = output
                        }

                        // ── Build feedback (includes PERSISTENT_REMINDER) ──
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

                        // Add feedback to conversation
                        conversation.add(mapOf("role" to "user", "content" to feedback))
                    }
                }
            }

            // ── HIT MAX STEPS — force final summary ─────────
            chatAdapter.appendThinkingLog(leoId, "[MAX $MAX_REACT_STEPS STEPS] Forcing final summary...")
            conversation.add(mapOf("role" to "user", "content" to
                "[SYSTEM: You have used $MAX_REACT_STEPS ReAct steps. " +
                "Output MISSION_COMPLETE now with a full, warm, detailed summary for JD of everything you accomplished.]"
            ))
            val finalPayload = LeoProtocol.buildReActPayloadWithReminder(conversation.drop(1), MAX_REACT_STEPS + 1)
            val finalRaw = networkClient.sendWithHistory(finalPayload)
            val finalCmd = CommandParser.parseSingle(finalRaw)
            val finalMsg = finalCmd.value.ifBlank {
                finalCmd.raw.optString("message", "Completed $MAX_REACT_STEPS steps on your mission, JD.")
            }
            withContext(Dispatchers.Main) {
                chatAdapter.finalizeLeoMessage(leoId, finalMsg)
                if (SecurityManager.isTTSEnabled()) speechManager.speak(finalMsg)
                finishDispatch()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                chatAdapter.appendThinkingLog(leoId, "[ERR] ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                chatAdapter.finalizeLeoMessage(leoId, "Network error on step $stepCount, JD: ${e.message?.take(100)}")
                finishDispatch()
            }
        }
    }

    /**
     * Read screen after UI control or browser nav.
     * For SHELL_EXEC/FS: the output IS the feedback — no screen read needed.
     */
    private fun shouldReadScreen(action: String): Boolean = when (action) {
        LeoProtocol.Action.UI_CONTROL,
        LeoProtocol.Action.BROWSER_NAVIGATE,
        LeoProtocol.Action.BROWSER_CLICK,
        LeoProtocol.Action.UI_CLICK,
        LeoProtocol.Action.WAIT_FOR -> true
        else                         -> false
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
        btnEnableAccessibility.text               = if (hasA) "✓ ON" else "ENABLE"
        btnEnableAccessibility.backgroundTintList = ColorStateList.valueOf(if (hasA) green else red)
        btnEnableAccessibility.isEnabled          = !hasA

        dot(dotOverlay, if (hasO) green else red)
        btnEnableOverlay.text               = if (hasO) "✓ ON" else "ENABLE"
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
