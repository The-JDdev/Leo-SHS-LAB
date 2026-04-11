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

/**
 * ══════════════════════════════════════════
 *  LEO ACTION EXECUTOR — LEVEL 5 EDITION
 *  SHS LAB v3 — AUTONOMOUS MISSION ENGINE
 *
 *  Core changes vs v2:
 *  • executeAndReport() — suspendable, returns MissionResult
 *  • Mission log — every action logs a human-readable step
 *  • WAIT_FOR dispatch — dynamic UI polling via AccessibilityService
 *  • READ_SCREEN dispatch — full screen dump for AI context
 *  • Mission report compiled after each step, fed back to AI
 * ══════════════════════════════════════════
 */
class ActionExecutor(private val context: Context) {

    /**
     * The result of executing one AI command:
     * @param isLogAction  True if the AI sent a LOG (mission done, show final message)
     * @param displayText  The human message to show in chat (only set if isLogAction=true)
     * @param report       Structured mission step report to feed back to AI
     */
    data class MissionResult(
        val isLogAction: Boolean,
        val displayText: String?,
        val report: String
    )

    private val fileEngine      = FileEngine()
    private val gitManager      = GitManager(context)
    private val shellBridge     = ShellBridge()
    private val hardwareManager = HardwareManager(context)

    /** Accumulated mission log for this step */
    private val missionLog = mutableListOf<String>()

    companion object {
        @Volatile private var activeExecutor: ActionExecutor? = null
        fun killAllTasks() { /* no-op in v3 — suspend model */ }
    }

    init { activeExecutor = this }

    // ══════════════════════════════════════════════════
    //  PRIMARY ENTRY POINT — LEVEL 5 AGENTIC LOOP
    // ══════════════════════════════════════════════════

    /**
     * Parse and execute one AI response. Returns a MissionResult so the
     * calling coroutine (MainActivity) can decide: show result or loop.
     *
     * Called on Dispatchers.IO — blocking operations are safe here.
     */
    suspend fun executeAndReport(aiResponse: String): MissionResult {
        missionLog.clear()

        val command = CommandParser.parse(aiResponse) ?: run {
            val errMsg = "Could not parse AI response: ${aiResponse.take(100)}"
            Logger.error(errMsg)
            missionLog.add("✗ PARSE_ERROR: $errMsg")
            return MissionResult(
                isLogAction = false,
                displayText = null,
                report = compileMissionReport()
            )
        }

        if (command.action == LeoProtocol.Action.LOG) {
            missionLog.add("✓ LOG: ${command.value.take(80)}")
            return MissionResult(
                isLogAction = true,
                displayText = command.value.ifBlank { "(Leo completed the mission)" },
                report = compileMissionReport()
            )
        }

        val stepResult = try {
            dispatchSingle(command)
        } catch (e: Exception) {
            Logger.error("Action [${command.action}] threw: ${e.message}")
            "error:${e.message ?: "exception"}"
        }

        missionLog.add(formatStep(command, stepResult))

        return MissionResult(
            isLogAction = false,
            displayText = null,
            report = compileMissionReport()
        )
    }

    // ══════════════════════════════════════════════════
    //  DISPATCHER — routes command to the right engine
    // ══════════════════════════════════════════════════

    private suspend fun dispatchSingle(cmd: CommandParser.LeoCommand): String {
        Logger.action("▶ ${cmd.action}${if (cmd.subAction.isNotEmpty()) ":${cmd.subAction}" else ""} → target='${cmd.target.take(60)}'")

        if (cmd.delayMs > 0) {
            Logger.action("⏱ Pre-delay: ${cmd.delayMs}ms")
            Thread.sleep(cmd.delayMs)
        }

        return when (cmd.action) {

            // ── Conversational output ───────────────────────
            LeoProtocol.Action.LOG -> {
                Logger.action("LOG: ${cmd.value.take(80)}")
                "chat_message_shown"
            }

            // ── UI_CONTROL (open app + WAIT_FOR sub_action) ─
            LeoProtocol.Action.UI_CONTROL -> executeUIControl(cmd)

            // ── READ_SCREEN — Leo's eyes ────────────────────
            LeoProtocol.Action.READ_SCREEN -> {
                val svc = LeoAccessibilityService.instance
                    ?: return "accessibility_service_not_connected"
                val screenText = svc.readScreenText()
                Logger.action("[EYES] READ_SCREEN complete: ${screenText.length} chars")
                screenText
            }

            // ── Standalone WAIT_FOR at root level ───────────
            LeoProtocol.Action.WAIT_FOR -> {
                val svc = LeoAccessibilityService.instance
                    ?: return "accessibility_service_not_connected"
                svc.waitForNode(cmd.target, cmd.timeoutMs)
            }

            // ── Legacy UI actions ───────────────────────────
            LeoProtocol.Action.UI_CLICK,
            LeoProtocol.Action.UI_TYPE,
            LeoProtocol.Action.UI_SCROLL -> executeLegacyUIAction(cmd)

            // ── File System ────────────────────────────────
            LeoProtocol.Action.FS_READ,
            LeoProtocol.Action.FS_WRITE,
            LeoProtocol.Action.FS_DELETE,
            LeoProtocol.Action.FS_MKDIR  -> executeFileAction(cmd)

            // ── Git ────────────────────────────────────────
            LeoProtocol.Action.GIT_INIT,
            LeoProtocol.Action.GIT_PUSH,
            LeoProtocol.Action.GIT_CLONE -> executeGitAction(cmd)

            // ── Native Hardware ────────────────────────────
            LeoProtocol.Action.HARDWARE_CONTROL -> executeHardwareAction(cmd)

            // ── Shell ──────────────────────────────────────
            LeoProtocol.Action.SHELL_EXEC -> {
                Logger.action("Shell: ${cmd.value.take(80)}")
                val output = shellBridge.exec(cmd.value)
                Logger.action("Shell output: ${output.take(200)}")
                output.take(1000)
            }

            // ── Static Wait (fallback) ─────────────────────
            LeoProtocol.Action.WAIT -> {
                val ms = cmd.value.toLongOrNull() ?: 500L
                Logger.action("⏱ Static wait: ${ms}ms")
                Thread.sleep(ms)
                "waited:${ms}ms"
            }

            else -> {
                Logger.warn("Unknown action: ${cmd.action}")
                "unknown_action:${cmd.action}"
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  UI_CONTROL HANDLER — handles sub_actions
    // ══════════════════════════════════════════════════

    private fun executeUIControl(cmd: CommandParser.LeoCommand): String {
        val svc = LeoAccessibilityService.instance
            ?: return "accessibility_service_not_connected"

        return when (cmd.subAction) {

            // ── WAIT_FOR: dynamic patience ──────────────────
            LeoProtocol.SubAction.WAIT_FOR -> {
                svc.waitForNode(cmd.target, cmd.timeoutMs)
            }

            // ── Default: open app or click element ──────────
            else -> {
                if (cmd.value.lowercase() == "open" && cmd.target.contains(".")) {
                    val launched = svc.launchAppByPackage(cmd.target)
                    Logger.action("UI_CONTROL: launch ${cmd.target} → $launched")
                    if (launched) "app_opened:${cmd.target}" else "launch_failed:${cmd.target}"
                } else if (cmd.value.lowercase() == "tap" || cmd.value.lowercase() == "click") {
                    val result = svc.findNodeAndClick(
                        textContent = cmd.target.takeIf { it.isNotEmpty() },
                        viewId      = null
                    )
                    if (result) "clicked:${cmd.target}" else "click_failed:${cmd.target}"
                } else {
                    val result = svc.findNodeAndClick(
                        textContent = cmd.value.takeIf { it.isNotEmpty() },
                        viewId      = cmd.target.takeIf { it.isNotEmpty() }
                    )
                    if (result) "clicked:${cmd.target}" else "click_failed:node_not_found"
                }
            }
        }
    }

    private fun executeLegacyUIAction(cmd: CommandParser.LeoCommand): String {
        val svc = LeoAccessibilityService.instance
            ?: return "accessibility_service_not_connected"
        return when (cmd.action) {
            LeoProtocol.Action.UI_CLICK -> {
                val result = svc.findNodeAndClick(
                    textContent = cmd.value.takeIf { it.isNotEmpty() },
                    viewId      = cmd.target.takeIf { it.isNotEmpty() }
                )
                if (result) "clicked:${cmd.target}" else "click_failed"
            }
            LeoProtocol.Action.UI_TYPE -> {
                val result = svc.injectTextToNode(
                    viewId     = cmd.target.takeIf { it.isNotEmpty() },
                    textToType = cmd.value
                )
                if (result) "typed:${cmd.target}" else "type_failed"
            }
            LeoProtocol.Action.UI_SCROLL -> {
                val result = svc.performScroll(cmd.value.ifEmpty { "down" })
                if (result) "scrolled:${cmd.value}" else "scroll_failed"
            }
            else -> "unhandled:${cmd.action}"
        }
    }

    // ══════════════════════════════════════════════════
    //  FILE, GIT, HARDWARE HANDLERS
    // ══════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════
    //  MISSION REPORT BUILDER
    // ══════════════════════════════════════════════════

    private fun formatStep(cmd: CommandParser.LeoCommand, result: String): String {
        val actionLabel = if (cmd.subAction.isNotEmpty()) "${cmd.action}:${cmd.subAction}" else cmd.action
        val success = !result.startsWith("error:") && !result.startsWith("failed") && !result.startsWith("timeout:")
        val icon = if (success) "✓" else "✗"
        return "$icon $actionLabel(target='${cmd.target.take(40)}') → $result"
    }

    private fun compileMissionReport(): String {
        if (missionLog.isEmpty()) return "no_steps_executed"
        return missionLog.joinToString("\n")
    }
}
