package com.shslab.leo.executor

import android.content.Context
import com.shslab.leo.accessibility.LeoAccessibilityService
import com.shslab.leo.browser.LeoBrowserEngine
import com.shslab.leo.core.LeoProtocol
import com.shslab.leo.core.Logger
import com.shslab.leo.file.FileEngine
import com.shslab.leo.git.GitManager
import com.shslab.leo.hardware.HardwareManager
import com.shslab.leo.parser.CommandParser
import com.shslab.leo.shell.ShellBridge

/**
 * ══════════════════════════════════════════════════════
 *  LEO ACTION EXECUTOR — APEX v5 — REACT SINGLE-STEP
 *  SHS LAB — OMNIPOTENT MISSION ENGINE
 *
 *  Executes ONE command at a time (ReAct architecture).
 *  Supports all engines:
 *   - AccessibilityService (UI_CONTROL)
 *   - LeoBrowserEngine (BROWSER_*)
 *   - ShellBridge (TERMINAL_EXEC)
 *   - FileEngine (FS_*)
 *   - HardwareManager (HARDWARE_CONTROL)
 *   - GitManager (GIT_*)
 *
 *  Returns StepResult with result string for AI feedback.
 * ══════════════════════════════════════════════════════
 */
class ActionExecutor(private val context: Context) {

    data class StepResult(
        val isMissionComplete: Boolean,
        val completionMessage: String?,
        val result: String,
        val isError: Boolean
    )

    private val fileEngine      = FileEngine()
    private val gitManager      = GitManager(context)
    private val shellBridge     = ShellBridge()
    private val hardwareManager = HardwareManager(context)
    private val browserEngine   by lazy { LeoBrowserEngine.getInstance(context) }

    companion object {
        @Volatile private var activeExecutor: ActionExecutor? = null
        fun killAllTasks() {}
    }

    init { activeExecutor = this }

    // ══════════════════════════════════════════════════
    //  PRIMARY: Execute a single command (ReAct step)
    // ══════════════════════════════════════════════════

    suspend fun executeSingle(cmd: CommandParser.LeoCommand): StepResult {

        // MISSION_COMPLETE — full mission done
        if (cmd.action == LeoProtocol.Action.MISSION_COMPLETE ||
            cmd.action == LeoProtocol.Action.LOG) {
            val message = cmd.value.ifBlank {
                cmd.raw.optString("message", "Mission complete, JD.")
            }
            Logger.action("[EXEC] MISSION_COMPLETE — message ready")
            return StepResult(
                isMissionComplete = true,
                completionMessage = message,
                result = "mission_complete",
                isError = false
            )
        }

        Logger.action("[EXEC] ${cmd.action}${if (cmd.subAction.isNotEmpty()) ":${cmd.subAction}" else ""} target='${cmd.target.take(40)}'")

        return try {
            if (cmd.delayMs > 0) Thread.sleep(cmd.delayMs)
            val result = dispatchSingle(cmd)
            val isErr  = result.startsWith("error:") || result.startsWith("timeout:") ||
                         result.startsWith("failed") || result.contains("not_found") ||
                         result.startsWith("launch_failed")
            Logger.action("[EXEC] Result: ${result.take(120)}")
            StepResult(false, null, result, isErr)
        } catch (e: Exception) {
            val err = "error:${e.javaClass.simpleName}:${e.message?.take(100)}"
            Logger.error("[EXEC] Exception: $err")
            StepResult(false, null, err, true)
        }
    }

    // ══════════════════════════════════════════════════
    //  BACKWARD COMPAT: Legacy array execution (v4)
    // ══════════════════════════════════════════════════

    data class MissionResult(
        val isLogAction: Boolean,
        val displayText: String?,
        val report: String
    )

    private val missionLog = mutableListOf<String>()

    suspend fun executeAndReport(aiResponse: String): MissionResult {
        missionLog.clear()
        val commands = CommandParser.parseMulti(aiResponse)
        if (commands.isEmpty()) {
            missionLog.add("PARSE_ERROR: ${aiResponse.take(100)}")
            return MissionResult(false, null, missionLog.joinToString("\n"))
        }
        for ((index, cmd) in commands.withIndex()) {
            if (cmd.action == LeoProtocol.Action.MISSION_COMPLETE ||
                cmd.action == LeoProtocol.Action.LOG) {
                val msg = cmd.value.ifBlank { cmd.raw.optString("message", "Done.") }
                return MissionResult(true, msg, missionLog.joinToString("\n"))
            }
            val sr = executeSingle(cmd)
            missionLog.add("[${index+1}] ${cmd.action}:${cmd.subAction} → ${sr.result}")
            if (sr.isMissionComplete) {
                return MissionResult(true, sr.completionMessage, missionLog.joinToString("\n"))
            }
        }
        return MissionResult(false, null, missionLog.joinToString("\n"))
    }

    // ══════════════════════════════════════════════════
    //  DISPATCHER — routes to correct engine
    // ══════════════════════════════════════════════════

    private suspend fun dispatchSingle(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {

            // ── AccessibilityService UI Engine ───────────────
            LeoProtocol.Action.UI_CONTROL   -> dispatchUIControl(cmd)

            // ── Leo's Eyes ───────────────────────────────────
            LeoProtocol.Action.READ_SCREEN  -> {
                val svc = LeoAccessibilityService.instance
                    ?: return "accessibility_not_connected:enable_in_settings"
                svc.readScreenText()
            }

            LeoProtocol.Action.WAIT_FOR -> {
                val svc = LeoAccessibilityService.instance
                    ?: return "accessibility_not_connected"
                svc.waitForNode(cmd.target, cmd.timeoutMs)
            }

            // ── Inbuilt Browser Engine ────────────────────────
            LeoProtocol.Action.BROWSER_NAVIGATE -> {
                Logger.action("[BROWSER] Navigate: ${cmd.target}")
                browserEngine.navigate(cmd.target.ifEmpty { cmd.value })
            }

            LeoProtocol.Action.BROWSER_CLICK -> {
                Logger.action("[BROWSER] Click: ${cmd.target}")
                browserEngine.click(cmd.target)
            }

            LeoProtocol.Action.BROWSER_TYPE -> {
                Logger.action("[BROWSER] Type into: ${cmd.target}")
                browserEngine.type(cmd.target, cmd.value)
            }

            LeoProtocol.Action.BROWSER_READ -> {
                Logger.action("[BROWSER] Reading page content")
                val content = browserEngine.readContent()
                Logger.action("[BROWSER] Content: ${content.take(80)}...")
                content
            }

            LeoProtocol.Action.BROWSER_JS -> {
                Logger.action("[BROWSER] JS: ${cmd.value.take(80)}")
                browserEngine.execJS(cmd.value)
            }

            // ── Terminal / Shell Engine ───────────────────────
            LeoProtocol.Action.TERMINAL_EXEC,
            LeoProtocol.Action.SHELL_EXEC -> {
                val command = cmd.value.ifBlank { cmd.target }
                Logger.action("[TERMINAL] Exec: ${command.take(80)}")
                val output = shellBridge.exec(command)
                Logger.action("[TERMINAL] Output: ${output.take(200)}")
                output.take(2000)
            }

            // ── File System ───────────────────────────────────
            LeoProtocol.Action.FS_READ,
            LeoProtocol.Action.FS_WRITE,
            LeoProtocol.Action.FS_DELETE,
            LeoProtocol.Action.FS_MKDIR  -> dispatchFileAction(cmd)

            // ── Git ───────────────────────────────────────────
            LeoProtocol.Action.GIT_INIT,
            LeoProtocol.Action.GIT_PUSH,
            LeoProtocol.Action.GIT_CLONE -> dispatchGitAction(cmd)

            // ── Hardware ──────────────────────────────────────
            LeoProtocol.Action.HARDWARE_CONTROL -> dispatchHardwareAction(cmd)

            // ── Static Wait ───────────────────────────────────
            LeoProtocol.Action.WAIT -> {
                val ms = cmd.value.toLongOrNull() ?: 500L
                Thread.sleep(ms.coerceAtMost(10000L))
                "waited:${ms}ms"
            }

            // ── Legacy UI aliases ─────────────────────────────
            LeoProtocol.Action.UI_CLICK -> {
                val svc = LeoAccessibilityService.instance ?: return "accessibility_not_connected"
                val ok = svc.findNodeAndClick(textContent = cmd.value.takeIf { it.isNotEmpty() }, viewId = cmd.target.takeIf { it.isNotEmpty() })
                if (ok) "clicked:${cmd.target}" else "click_failed:${cmd.target}"
            }
            LeoProtocol.Action.UI_TYPE -> {
                val svc = LeoAccessibilityService.instance ?: return "accessibility_not_connected"
                val ok = svc.injectTextToNode(viewId = cmd.target.takeIf { it.isNotEmpty() }, textToType = cmd.value)
                if (ok) "typed:${cmd.target}" else "type_failed:${cmd.target}"
            }
            LeoProtocol.Action.UI_SCROLL -> {
                val svc = LeoAccessibilityService.instance ?: return "accessibility_not_connected"
                val ok = svc.performScroll(cmd.value.ifEmpty { "down" })
                if (ok) "scrolled:${cmd.value}" else "scroll_failed"
            }

            else -> {
                Logger.warn("[EXEC] Unknown action: ${cmd.action}")
                "unknown_action:${cmd.action}"
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  UI_CONTROL — All sub_actions
    // ══════════════════════════════════════════════════

    private fun dispatchUIControl(cmd: CommandParser.LeoCommand): String {
        val svc = LeoAccessibilityService.instance
            ?: return "accessibility_service_not_connected:enable_in_settings"

        return when (cmd.subAction) {

            LeoProtocol.SubAction.OPEN_APP -> {
                val pkg = cmd.target.trim()
                if (pkg.isEmpty()) return "error:OPEN_APP needs target package"
                val ok = svc.launchAppByPackage(pkg)
                if (ok) "app_opened:$pkg" else "launch_failed:$pkg:not_installed"
            }

            LeoProtocol.SubAction.CLICK -> {
                val ok = svc.findNodeAndClick(
                    textContent = cmd.target.takeIf { it.isNotEmpty() },
                    viewId      = cmd.value.takeIf  { it.isNotEmpty() }
                )
                if (ok) "clicked:'${cmd.target}'" else "click_failed:'${cmd.target}':not_found"
            }

            LeoProtocol.SubAction.LONG_PRESS -> {
                val ok = svc.findNodeAndLongPress(
                    textContent = cmd.target.takeIf { it.isNotEmpty() },
                    viewId      = cmd.value.takeIf  { it.isNotEmpty() }
                )
                if (ok) "long_pressed:'${cmd.target}'" else "long_press_failed"
            }

            LeoProtocol.SubAction.TYPE -> {
                val text = cmd.value.ifEmpty { cmd.raw.optString("value", "") }
                val ok   = svc.injectTextToNode(
                    viewId      = cmd.target.takeIf { it.isNotEmpty() },
                    textContent = cmd.target.takeIf { it.isNotEmpty() },
                    textToType  = text
                )
                if (ok) "typed:'${text.take(30)}' into '${cmd.target}'" else "type_failed:'${cmd.target}'"
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
                if (svc.pressBack()) "back_pressed" else "back_failed"
            }

            LeoProtocol.SubAction.HOME -> {
                if (svc.pressHome()) "home_pressed" else "home_failed"
            }

            "" -> {
                val v = cmd.value.lowercase().trim()
                when {
                    v == "open" && cmd.target.contains(".") -> {
                        if (svc.launchAppByPackage(cmd.target)) "app_opened:${cmd.target}" else "launch_failed:${cmd.target}"
                    }
                    v == "tap" || v == "click" -> {
                        if (svc.findNodeAndClick(textContent = cmd.target.takeIf { it.isNotEmpty() })) "clicked:'${cmd.target}'" else "click_failed:'${cmd.target}'"
                    }
                    v == "back" -> { svc.pressBack(); "back_pressed" }
                    v == "home" -> { svc.pressHome(); "home_pressed" }
                    else -> {
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
    //  File / Git / Hardware
    // ══════════════════════════════════════════════════

    private fun dispatchFileAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {
            LeoProtocol.Action.FS_WRITE  -> { fileEngine.writeCodeFile(cmd.target, cmd.value); "written:${cmd.target}" }
            LeoProtocol.Action.FS_READ   -> { val c = fileEngine.readFileContent(cmd.target); c.take(3000) }
            LeoProtocol.Action.FS_DELETE -> { fileEngine.deleteTarget(cmd.target, cmd.value); "deleted:${cmd.target}" }
            LeoProtocol.Action.FS_MKDIR  -> { fileEngine.createDirectory(cmd.target); "mkdir:${cmd.target}" }
            else -> "unknown_fs"
        }
    }

    private fun dispatchGitAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {
            LeoProtocol.Action.GIT_INIT  -> gitManager.initAndLink(cmd.target, cmd.value)
            LeoProtocol.Action.GIT_PUSH  -> gitManager.pushAll(cmd.target, cmd.value)
            LeoProtocol.Action.GIT_CLONE -> gitManager.cloneRepo(cmd.target, cmd.value)
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
            else -> "unknown_hardware:${cmd.target}"
        }
    }
}
