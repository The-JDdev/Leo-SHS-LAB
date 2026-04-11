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
 *  LEO ACTION EXECUTOR — ABSOLUTE SOVEREIGNTY
 *  SHS LAB v4 — OMNIPOTENT MISSION ENGINE
 *
 *  Executes JSON arrays of commands sequentially.
 *  Every step compiles into a mission log.
 *  Full sub_action support: OPEN_APP, CLICK, TYPE,
 *  LONG_PRESS, SCROLL, WAIT_FOR, BACK, HOME.
 *  Expanded hardware: volume, brightness, wifi, camera.
 * ══════════════════════════════════════════
 */
class ActionExecutor(private val context: Context) {

    data class MissionResult(
        val isLogAction: Boolean,
        val displayText: String?,
        val report: String
    )

    private val fileEngine      = FileEngine()
    private val gitManager      = GitManager(context)
    private val shellBridge     = ShellBridge()
    private val hardwareManager = HardwareManager(context)

    private val missionLog = mutableListOf<String>()

    companion object {
        @Volatile private var activeExecutor: ActionExecutor? = null
        fun killAllTasks() {}
    }

    init { activeExecutor = this }

    // ══════════════════════════════════════════════════
    //  PRIMARY: Execute full command array
    // ══════════════════════════════════════════════════

    /**
     * Parse + execute the full array of commands from one AI response.
     * Stops at the first LOG action (mission done) or runs all steps.
     *
     * Called on Dispatchers.IO — all blocking operations safe here.
     */
    suspend fun executeAndReport(aiResponse: String): MissionResult {
        missionLog.clear()

        val commands = CommandParser.parseMulti(aiResponse)

        if (commands.isEmpty()) {
            val errMsg = "No parseable commands in AI response"
            Logger.error(errMsg)
            missionLog.add("✗ PARSE_ERROR: ${aiResponse.take(100)}")
            return MissionResult(false, null, compileMissionReport())
        }

        Logger.action("[EXEC] Executing ${commands.size} command${if (commands.size > 1) "s" else ""}")

        for ((index, cmd) in commands.withIndex()) {
            val stepNum = index + 1

            // LOG = mission done — extract message and return immediately
            if (cmd.action == LeoProtocol.Action.LOG) {
                val message = cmd.value.ifBlank { cmd.raw.optString("message", "Mission complete.") }
                missionLog.add("✓ [Step $stepNum] LOG → display message to JD")
                Logger.action("[EXEC] Mission complete after $stepNum step(s)")
                return MissionResult(true, message, compileMissionReport())
            }

            // Execute the action
            Logger.action("[EXEC] Step $stepNum/${commands.size}: ${cmd.action}${if (cmd.subAction.isNotEmpty()) ":${cmd.subAction}" else ""}")
            val stepResult = try {
                dispatchSingle(cmd)
            } catch (e: Exception) {
                Logger.error("[EXEC] Step $stepNum threw: ${e.message}")
                "error:${e.message ?: "exception"}"
            }

            missionLog.add(formatStep(stepNum, cmd, stepResult))
        }

        // All commands executed — no LOG yet, report back to AI
        Logger.action("[EXEC] Batch complete — ${commands.size} steps done, awaiting next instruction")
        return MissionResult(false, null, compileMissionReport())
    }

    // ══════════════════════════════════════════════════
    //  DISPATCHER
    // ══════════════════════════════════════════════════

    private suspend fun dispatchSingle(cmd: CommandParser.LeoCommand): String {
        if (cmd.delayMs > 0) {
            Logger.action("⏱ Pre-delay: ${cmd.delayMs}ms")
            Thread.sleep(cmd.delayMs)
        }

        return when (cmd.action) {

            // ── UI_CONTROL (central — handles all sub_actions) ──────────
            LeoProtocol.Action.UI_CONTROL -> dispatchUIControl(cmd)

            // ── READ_SCREEN — Leo's eyes ─────────────────────────────────
            LeoProtocol.Action.READ_SCREEN -> {
                val svc = LeoAccessibilityService.instance
                    ?: return "accessibility_service_not_connected:enable_in_settings"
                svc.readScreenText()
            }

            // ── Standalone WAIT_FOR (root-level alias) ───────────────────
            LeoProtocol.Action.WAIT_FOR -> {
                val svc = LeoAccessibilityService.instance
                    ?: return "accessibility_service_not_connected"
                svc.waitForNode(cmd.target, cmd.timeoutMs)
            }

            // ── Legacy aliases (backward compat) ─────────────────────────
            LeoProtocol.Action.UI_CLICK -> {
                val svc = LeoAccessibilityService.instance ?: return "accessibility_not_connected"
                val ok = svc.findNodeAndClick(
                    textContent = cmd.value.takeIf { it.isNotEmpty() },
                    viewId      = cmd.target.takeIf { it.isNotEmpty() }
                )
                if (ok) "clicked:${cmd.target}" else "click_failed"
            }
            LeoProtocol.Action.UI_TYPE -> {
                val svc = LeoAccessibilityService.instance ?: return "accessibility_not_connected"
                val ok = svc.injectTextToNode(viewId = cmd.target.takeIf { it.isNotEmpty() }, textToType = cmd.value)
                if (ok) "typed:${cmd.target}" else "type_failed"
            }
            LeoProtocol.Action.UI_SCROLL -> {
                val svc = LeoAccessibilityService.instance ?: return "accessibility_not_connected"
                val ok = svc.performScroll(cmd.value.ifEmpty { "down" })
                if (ok) "scrolled:${cmd.value}" else "scroll_failed"
            }

            // ── File System ──────────────────────────────────────────────
            LeoProtocol.Action.FS_READ,
            LeoProtocol.Action.FS_WRITE,
            LeoProtocol.Action.FS_DELETE,
            LeoProtocol.Action.FS_MKDIR  -> dispatchFileAction(cmd)

            // ── Git ──────────────────────────────────────────────────────
            LeoProtocol.Action.GIT_INIT,
            LeoProtocol.Action.GIT_PUSH,
            LeoProtocol.Action.GIT_CLONE -> dispatchGitAction(cmd)

            // ── Hardware ─────────────────────────────────────────────────
            LeoProtocol.Action.HARDWARE_CONTROL -> dispatchHardwareAction(cmd)

            // ── Shell (curl / pm / am only) ──────────────────────────────
            LeoProtocol.Action.SHELL_EXEC -> {
                Logger.action("Shell: ${cmd.value.take(80)}")
                val output = shellBridge.exec(cmd.value)
                Logger.action("Shell output: ${output.take(200)}")
                output.take(1000)
            }

            // ── Static wait (last resort) ────────────────────────────────
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
    //  UI_CONTROL — ALL SUB_ACTIONS
    // ══════════════════════════════════════════════════

    private fun dispatchUIControl(cmd: CommandParser.LeoCommand): String {
        val svc = LeoAccessibilityService.instance
            ?: return "accessibility_service_not_connected:enable_in_settings"

        return when (cmd.subAction) {

            LeoProtocol.SubAction.OPEN_APP -> {
                val pkg = cmd.target.trim()
                if (pkg.isEmpty()) return "error:OPEN_APP requires target package name"
                val ok = svc.launchAppByPackage(pkg)
                if (ok) "app_opened:$pkg" else "launch_failed:$pkg:not_installed"
            }

            LeoProtocol.SubAction.CLICK -> {
                val ok = svc.findNodeAndClick(
                    textContent = cmd.target.takeIf { it.isNotEmpty() },
                    viewId      = cmd.value.takeIf { it.isNotEmpty() }
                )
                if (ok) "clicked:'${cmd.target}'" else "click_failed:'${cmd.target}':not_found"
            }

            LeoProtocol.SubAction.LONG_PRESS -> {
                val ok = svc.findNodeAndLongPress(
                    textContent = cmd.target.takeIf { it.isNotEmpty() },
                    viewId      = cmd.value.takeIf { it.isNotEmpty() }
                )
                if (ok) "long_pressed:'${cmd.target}'" else "long_press_failed"
            }

            LeoProtocol.SubAction.TYPE -> {
                val textToType = cmd.value.ifEmpty { cmd.raw.optString("value", "") }
                val ok = svc.injectTextToNode(
                    viewId      = cmd.target.takeIf { it.isNotEmpty() },
                    textContent = cmd.target.takeIf { it.isNotEmpty() },
                    textToType  = textToType
                )
                if (ok) "typed:'${textToType.take(30)}' into '${cmd.target}'"
                else    "type_failed:'${cmd.target}'"
            }

            LeoProtocol.SubAction.SCROLL -> {
                val dir = cmd.value.ifEmpty { "down" }
                val ok  = svc.performScroll(dir)
                if (ok) "scrolled:$dir" else "scroll_failed"
            }

            LeoProtocol.SubAction.WAIT_FOR -> {
                svc.waitForNode(cmd.target, cmd.timeoutMs)
            }

            LeoProtocol.SubAction.BACK -> {
                val ok = svc.pressBack()
                if (ok) "back_pressed" else "back_failed"
            }

            LeoProtocol.SubAction.HOME -> {
                val ok = svc.pressHome()
                if (ok) "home_pressed" else "home_failed"
            }

            // No sub_action — infer from value ("open", "tap", "click", package name)
            "" -> {
                val valueLower = cmd.value.lowercase().trim()
                when {
                    valueLower == "open" && cmd.target.contains(".") -> {
                        val ok = svc.launchAppByPackage(cmd.target)
                        if (ok) "app_opened:${cmd.target}" else "launch_failed:${cmd.target}"
                    }
                    valueLower == "tap" || valueLower == "click" -> {
                        val ok = svc.findNodeAndClick(textContent = cmd.target.takeIf { it.isNotEmpty() })
                        if (ok) "clicked:'${cmd.target}'" else "click_failed:'${cmd.target}'"
                    }
                    valueLower == "back" -> {
                        svc.pressBack(); "back_pressed"
                    }
                    valueLower == "home" -> {
                        svc.pressHome(); "home_pressed"
                    }
                    else -> {
                        // Generic click by target text
                        val ok = svc.findNodeAndClick(
                            textContent = cmd.target.takeIf { it.isNotEmpty() },
                            viewId      = cmd.target.takeIf { it.isNotEmpty() }
                        )
                        if (ok) "clicked:'${cmd.target}'" else "click_failed:'${cmd.target}'"
                    }
                }
            }

            else -> "unknown_sub_action:${cmd.subAction}"
        }
    }

    // ══════════════════════════════════════════════════
    //  FILE / GIT / HARDWARE
    // ══════════════════════════════════════════════════

    private fun dispatchFileAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {
            LeoProtocol.Action.FS_WRITE  -> { fileEngine.writeCodeFile(cmd.target, cmd.value); Logger.file("Written: ${cmd.target}"); "written:${cmd.target}" }
            LeoProtocol.Action.FS_READ   -> { val c = fileEngine.readFileContent(cmd.target); Logger.file("Read: ${cmd.target} (${c.length} bytes)"); c.take(2000) }
            LeoProtocol.Action.FS_DELETE -> { fileEngine.deleteTarget(cmd.target, cmd.value); Logger.file("Deleted: ${cmd.target}"); "deleted:${cmd.target}" }
            LeoProtocol.Action.FS_MKDIR  -> { fileEngine.createDirectory(cmd.target); Logger.file("Dir: ${cmd.target}"); "mkdir:${cmd.target}" }
            else -> "unknown_fs"
        }
    }

    private fun dispatchGitAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {
            LeoProtocol.Action.GIT_INIT  -> { Logger.git("Init: ${cmd.target}"); gitManager.initAndLink(cmd.target, cmd.value) }
            LeoProtocol.Action.GIT_PUSH  -> { Logger.git("Push: ${cmd.target}"); gitManager.pushAll(cmd.target, cmd.value) }
            LeoProtocol.Action.GIT_CLONE -> { Logger.git("Clone: ${cmd.target}"); gitManager.cloneRepo(cmd.target, cmd.value) }
            else -> "unknown_git"
        }
    }

    private fun dispatchHardwareAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.target.lowercase().trim()) {
            HardwareManager.TARGET_FLASHLIGHT -> hardwareManager.setFlashlight(cmd.value.lowercase() == "on")
            HardwareManager.TARGET_VIBRATE    -> hardwareManager.vibrate(cmd.value.toLongOrNull() ?: 200L)
            HardwareManager.TARGET_BATTERY    -> hardwareManager.getBatteryLevel()
            HardwareManager.TARGET_VOLUME     -> hardwareManager.setVolume(cmd.value)
            HardwareManager.TARGET_BRIGHTNESS -> hardwareManager.setBrightness(cmd.value)
            HardwareManager.TARGET_WIFI       -> hardwareManager.setWifi(cmd.value)
            HardwareManager.TARGET_CAMERA     -> hardwareManager.openCamera()
            else -> { Logger.warn("Unknown hardware target: ${cmd.target}"); "unknown_hardware:${cmd.target}" }
        }
    }

    // ══════════════════════════════════════════════════
    //  MISSION REPORT
    // ══════════════════════════════════════════════════

    private fun formatStep(step: Int, cmd: CommandParser.LeoCommand, result: String): String {
        val label   = if (cmd.subAction.isNotEmpty()) "${cmd.action}:${cmd.subAction}" else cmd.action
        val success = !result.startsWith("error:") && !result.startsWith("timeout:")
        val icon    = if (success) "✓" else "✗"
        return "$icon [Step $step] $label('${cmd.target.take(35)}') → $result"
    }

    private fun compileMissionReport(): String {
        return if (missionLog.isEmpty()) "no_steps_executed" else missionLog.joinToString("\n")
    }
}
