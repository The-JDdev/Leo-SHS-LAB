package com.shslab.leo.executor

import android.content.Context
import com.shslab.leo.accessibility.LeoAccessibilityService
import com.shslab.leo.core.LeoProtocol
import com.shslab.leo.core.Logger
import com.shslab.leo.file.FileEngine
import com.shslab.leo.git.GitManager
import com.shslab.leo.hardware.HardwareManager
import com.shslab.leo.parser.CommandParser
import com.shslab.leo.shell.ShellBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ══════════════════════════════════════════
 *  LEO ACTION EXECUTOR — CENTRAL ROUTER
 *  SHS LAB v2 — UNLIMITED EDITION
 *
 *  Routes parsed commands to the correct engine.
 *  All execution is SILENT — logs go to the Thinking
 *  accordion, not the chat. Chat only sees LOG results.
 * ══════════════════════════════════════════
 */
class ActionExecutor(private val context: Context) {

    private val scope           = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue           = CommandQueue()
    private val fileEngine      = FileEngine()
    private val gitManager      = GitManager(context)
    private val shellBridge     = ShellBridge()
    private val hardwareManager = HardwareManager(context)

    /** Last execution feedback — injected into next AI call as context */
    var lastFeedback: String = ""
        private set

    companion object {
        @Volatile private var activeExecutor: ActionExecutor? = null
        fun killAllTasks() { activeExecutor?.queue?.kill() }
    }

    init { activeExecutor = this }

    /**
     * Entry point: parse the AI JSON response and execute.
     * Call this from the main thread; execution moves to IO internally.
     */
    fun execute(aiResponse: String) {
        val command = CommandParser.parse(aiResponse) ?: run {
            lastFeedback = CommandParser.buildFeedback("error", "PARSE", "Could not parse AI response")
            Logger.error("Parse failure — raw response: ${aiResponse.take(200)}")
            return
        }
        queue.enqueue(command)
        scope.launch { queue.drainWith(this@ActionExecutor) }
    }

    /**
     * Dispatch a single command. Called by CommandQueue.drainWith on IO thread.
     */
    suspend fun dispatchSingle(cmd: CommandParser.LeoCommand) {
        Logger.action("▶ ${cmd.action} → target:${cmd.target.take(60)}")

        lastFeedback = try {
            val result = when (cmd.action) {
                // ── Conversational LOG (appears in chat) ──────
                LeoProtocol.Action.LOG -> {
                    Logger.action("LOG resolved: ${cmd.value.take(80)}")
                    "chat_message_shown"
                }

                // ── UI Control (AccessibilityService) ────────
                LeoProtocol.Action.UI_CONTROL,
                LeoProtocol.Action.UI_CLICK,
                LeoProtocol.Action.UI_TYPE,
                LeoProtocol.Action.UI_SCROLL -> executeUIAction(cmd)

                // ── File System ──────────────────────────────
                LeoProtocol.Action.FS_READ,
                LeoProtocol.Action.FS_WRITE,
                LeoProtocol.Action.FS_DELETE,
                LeoProtocol.Action.FS_MKDIR  -> executeFileAction(cmd)

                // ── Git ──────────────────────────────────────
                LeoProtocol.Action.GIT_INIT,
                LeoProtocol.Action.GIT_PUSH,
                LeoProtocol.Action.GIT_CLONE -> executeGitAction(cmd)

                // ── Native Hardware (no shell, no SecurityException) ──
                LeoProtocol.Action.HARDWARE_CONTROL -> executeHardwareAction(cmd)

                // ── Shell (use sparingly, Android 10 restrictions apply) ──
                LeoProtocol.Action.SHELL_EXEC -> executeShellAction(cmd)

                // ── Wait ─────────────────────────────────────
                LeoProtocol.Action.WAIT -> {
                    val ms = cmd.value.toLongOrNull() ?: 500L
                    Logger.action("⏱ Wait ${ms}ms")
                    Thread.sleep(ms)
                    "waited:${ms}ms"
                }

                else -> {
                    Logger.warn("Unknown action type: ${cmd.action}")
                    "unknown_action:${cmd.action}"
                }
            }
            CommandParser.buildFeedback("success", cmd.action, result)
        } catch (e: Exception) {
            Logger.error("Action [${cmd.action}] threw: ${e.message}")
            CommandParser.buildFeedback("error", cmd.action, e.message ?: "exception")
        }
    }

    // ══════════════════════════════════════════════════
    //  HANDLERS
    // ══════════════════════════════════════════════════

    private fun executeUIAction(cmd: CommandParser.LeoCommand): String {
        val svc = LeoAccessibilityService.instance
            ?: return "accessibility_service_not_connected"

        return when (cmd.action) {
            LeoProtocol.Action.UI_CONTROL -> {
                // Open app by package name, or perform generic UI command
                if (cmd.value == "open" && cmd.target.contains(".")) {
                    val launched = svc.launchAppByPackage(cmd.target)
                    Logger.action("UI_CONTROL: launch ${cmd.target} → $launched")
                    if (launched) "app_opened:${cmd.target}" else "launch_failed:${cmd.target}"
                } else {
                    val result = svc.findNodeAndClick(
                        textContent = cmd.value.takeIf { it.isNotEmpty() },
                        viewId      = cmd.target.takeIf { it.isNotEmpty() }
                    )
                    if (result) "clicked:${cmd.target}" else "click_failed:node_not_found"
                }
            }
            LeoProtocol.Action.UI_CLICK -> {
                val result = svc.findNodeAndClick(
                    textContent = cmd.value.takeIf { it.isNotEmpty() },
                    viewId      = cmd.target.takeIf { it.isNotEmpty() }
                )
                if (result) "clicked:${cmd.target}" else "click_failed:node_not_found"
            }
            LeoProtocol.Action.UI_TYPE -> {
                val result = svc.injectTextToNode(
                    viewId    = cmd.target.takeIf { it.isNotEmpty() },
                    textToType = cmd.value
                )
                if (result) "typed:${cmd.target}" else "type_failed"
            }
            LeoProtocol.Action.UI_SCROLL -> {
                val result = svc.performScroll(cmd.value.ifEmpty { "down" })
                if (result) "scrolled:${cmd.value}" else "scroll_failed"
            }
            else -> "unhandled_ui_action:${cmd.action}"
        }
    }

    private fun executeFileAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {
            LeoProtocol.Action.FS_WRITE -> {
                fileEngine.writeCodeFile(cmd.target, cmd.value)
                Logger.file("Written: ${cmd.target}")
                "written:${cmd.target}"
            }
            LeoProtocol.Action.FS_READ -> {
                val content = fileEngine.readFileContent(cmd.target)
                Logger.file("Read: ${cmd.target} (${content.length} bytes)")
                content.take(2000)
            }
            LeoProtocol.Action.FS_DELETE -> {
                fileEngine.deleteTarget(cmd.target, cmd.value)
                Logger.file("Deleted: ${cmd.target}")
                "deleted:${cmd.target}"
            }
            LeoProtocol.Action.FS_MKDIR -> {
                fileEngine.createDirectory(cmd.target)
                Logger.file("Dir created: ${cmd.target}")
                "mkdir:${cmd.target}"
            }
            else -> "unknown_fs_action"
        }
    }

    private fun executeGitAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {
            LeoProtocol.Action.GIT_INIT  -> { Logger.git("Init: ${cmd.target}"); gitManager.initAndLink(cmd.target, cmd.value) }
            LeoProtocol.Action.GIT_PUSH  -> { Logger.git("Push: ${cmd.target}"); gitManager.pushAll(cmd.target, cmd.value) }
            LeoProtocol.Action.GIT_CLONE -> { Logger.git("Clone: ${cmd.target}"); gitManager.cloneRepo(cmd.target, cmd.value) }
            else -> "unknown_git_action"
        }
    }

    /**
     * HARDWARE_CONTROL — uses native Android APIs only.
     * No shell, no SecurityException, works on API 29+.
     *
     * Supported targets: flashlight, vibrate, battery
     * Values: "on" | "off" | duration_ms (vibrate) | "check" (battery)
     */
    private fun executeHardwareAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.target.lowercase()) {
            HardwareManager.TARGET_FLASHLIGHT -> {
                val on = cmd.value.lowercase() == "on"
                hardwareManager.setFlashlight(on)
            }
            HardwareManager.TARGET_VIBRATE -> {
                val ms = cmd.value.toLongOrNull() ?: 200L
                hardwareManager.vibrate(ms)
            }
            HardwareManager.TARGET_BATTERY -> {
                hardwareManager.getBatteryLevel()
            }
            else -> {
                Logger.warn("Unknown hardware target: ${cmd.target}")
                "unknown_hardware_target:${cmd.target}"
            }
        }
    }

    private fun executeShellAction(cmd: CommandParser.LeoCommand): String {
        Logger.action("Shell: ${cmd.value.take(80)}")
        val output = shellBridge.exec(cmd.value)
        Logger.action("Shell output: ${output.take(200)}")
        return output.take(1000)
    }
}
