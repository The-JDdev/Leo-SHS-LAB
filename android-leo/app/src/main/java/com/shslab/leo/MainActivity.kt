package com.shslab.leo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
    private lateinit var overlayButton: Button
    private lateinit var logoImageView: ImageView

    private val networkClient by lazy { LeoNetworkClient() }
    private val actionExecutor by lazy { ActionExecutor(this) }

    companion object {
        private const val REQ_PERMISSIONS = 100
        private const val REQ_OVERLAY     = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize encrypted vault FIRST
        SecurityManager.init(this)

        setContentView(R.layout.activity_main)

        bindViews()
        Logger.attach(terminalTextView, terminalScrollView)

        bootSequence()
        requestRequiredPermissions()
    }

    private fun bindViews() {
        logoImageView      = findViewById(R.id.imgLeoLogo)
        terminalScrollView = findViewById(R.id.scrollTerminal)
        terminalTextView   = findViewById(R.id.tvTerminal)
        inputEditText      = findViewById(R.id.etInput)
        sendButton         = findViewById(R.id.btnSend)
        overlayButton      = findViewById(R.id.btnOverlay)

        sendButton.setOnClickListener { dispatchUserCommand() }
        overlayButton.setOnClickListener { toggleOverlay() }
    }

    private fun bootSequence() {
        Logger.system("════════════════════════════════")
        Logger.system("  LEO v1.0.0 — SHS LAB ONLINE  ")
        Logger.system("════════════════════════════════")
        Logger.system("Kernel boot sequence initiated...")
        Logger.leo("Identity protocol loaded.")
        Logger.system("Encrypted vault: UNLOCKED")
        Logger.system("Active provider: ${SecurityManager.getActiveProvider().uppercase()}")
        Logger.system("Ready for operator input.")
        Logger.raw("────────────────────────────────")
    }

    private fun dispatchUserCommand() {
        val userInput = inputEditText.text.toString().trim()
        if (userInput.isEmpty()) return

        inputEditText.setText("")
        Logger.leo("Operator → $userInput")
        Logger.net("[Leo]: Establishing direct uplink...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = networkClient.sendPrompt(userInput)
                withContext(Dispatchers.Main) {
                    Logger.net("[Leo]: Parsing AI Payload...")
                    actionExecutor.execute(response)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Logger.error("Uplink failure: ${e.message}")
                }
            }
        }
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQ_OVERLAY)
            Logger.warn("SYSTEM_ALERT_WINDOW permission required. Redirecting...")
            return
        }
        val svc = Intent(this, OverlayService::class.java)
        startForegroundService(svc)
        Logger.system("Dynamic Island overlay activated.")
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        val required = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        required.forEach { perm ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                needed.add(perm)
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        else
            Logger.system("Storage permissions: GRANTED")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) Logger.system("Storage permissions: GRANTED")
            else Logger.warn("Storage permissions: DENIED — FS engine limited")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.detach()
    }
}
