package com.shslab.leo.executor

import android.content.Context
import com.shslab.leo.accessibility.LeoAccessibilityService
import com.shslab.leo.core.LeoProtocol
import com.shslab.leo.core.Logger
import com.shslab.leo.file.FileEngine
import com.shslab.leo.git.GitManager
import com.shslab.leo.parser.CommandParser
import com.shslab.leo.shell.ShellBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ══════════════════════════════════════════
 *  LEO ACTION EXECUTOR — CENTRAL ROUTER
 *  SHS LAB
 *
 *  Routes parsed commands to the correct engine.
 *  Generates structured JSON feedback for next AI call.
 * ══════════════════════════════════════════
 */
class ActionExecutor(private val context: Context) {

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue       = CommandQueue()
    private val fileEngine  = FileEngine()
    private val gitManager  = GitManager(context)
    private val shellBridge = ShellBridge()

    /** Last execution feedback — injected into next AI call */
    var lastFeedback: String = ""
        private set

    companion object {
        @Volatile
        private var activeExecutor: ActionExecutor? = null

        fun killAllTasks() {
            activeExecutor?.queue?.kill()
            activeExecutor?.scope?.let { /* cancel children */ }
            System.gc()
        }
    }

    init { activeExecutor = this }

    /**
     * Entry point: parse AI response and execute.
     */
    fun execute(aiResponse: String) {
        val command = CommandParser.parse(aiResponse) ?: run {
            lastFeedback = CommandParser.buildFeedback(
                "error", "PARSE", "Failed to parse AI response"
            )
            Logger.error("No valid command in AI response — skipping execution")
            return
        }
        queue.enqueue(command)
        scope.launch { queue.drainWith(this@ActionExecutor) }
    }

    /**
     * Execute a single parsed command (called by CommandQueue.drainWith).
     */
    suspend fun dispatchSingle(cmd: CommandParser.LeoCommand) {
        Logger.action("[Leo]: Dispatching Action → ${cmd.action}")
        lastFeedback = try {
            val result = when (cmd.action) {
                LeoProtocol.Action.UI_CLICK   -> executeUIAction(cmd)
                LeoProtocol.Action.UI_TYPE    -> executeUIAction(cmd)
                LeoProtocol.Action.UI_SCROLL  -> executeUIAction(cmd)
                LeoProtocol.Action.FS_READ    -> executeFileAction(cmd)
                LeoProtocol.Action.FS_WRITE   -> executeFileAction(cmd)
                LeoProtocol.Action.FS_DELETE  -> executeFileAction(cmd)
                LeoProtocol.Action.FS_MKDIR   -> executeFileAction(cmd)
                LeoProtocol.Action.GIT_INIT   -> executeGitAction(cmd)
                LeoProtocol.Action.GIT_PUSH   -> executeGitAction(cmd)
                LeoProtocol.Action.GIT_CLONE  -> executeGitAction(cmd)
                LeoProtocol.Action.SHELL_EXEC -> executeShellAction(cmd)
                LeoProtocol.Action.LOG        -> { Logger.leo(cmd.value); "logged" }
                LeoProtocol.Action.WAIT       -> { Thread.sleep(cmd.value.toLongOrNull() ?: 500L); "waited" }
                else -> {
                    Logger.warn("Unknown action: ${cmd.action}")
                    "unknown_action"
                }
            }
            CommandParser.buildFeedback("success", cmd.action, result)
        } catch (e: Exception) {
            Logger.error("Action failed [${cmd.action}]: ${e.message}")
            CommandParser.buildFeedback("error", cmd.action, e.message ?: "unknown error")
        }
    }

    // ════════════════════════════════════════════
    //  ACTION ROUTE HANDLERS
    // ════════════════════════════════════════════

    private fun executeUIAction(cmd: CommandParser.LeoCommand): String {
        val accessibility = LeoAccessibilityService.instance
            ?: return "Accessibility service not connected — enable in Settings"

        return when (cmd.action) {
            LeoProtocol.Action.UI_CLICK -> {
                val result = accessibility.findNodeAndClick(
                    textContent = cmd.value.takeIf { it.isNotEmpty() },
                    viewId      = cmd.target.takeIf { it.isNotEmpty() }
                )
                if (result) "Clicked: ${cmd.target}" else "Click failed: node not found"
            }
            LeoProtocol.Action.UI_TYPE -> {
                val result = accessibility.injectTextToNode(
                    viewId      = cmd.target.takeIf { it.isNotEmpty() },
                    textToType  = cmd.value
                )
                if (result) "Typed into: ${cmd.target}" else "Type failed"
            }
            LeoProtocol.Action.UI_SCROLL -> {
                val result = accessibility.performScroll(cmd.value.ifEmpty { "down" })
                if (result) "Scrolled: ${cmd.value}" else "Scroll failed"
            }
            else -> "Unhandled UI action"
        }
    }

    private fun executeFileAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {
            LeoProtocol.Action.FS_WRITE -> {
                fileEngine.writeCodeFile(cmd.target, cmd.value)
                Logger.file("[FS] Written: ${cmd.target}")
                "File written: ${cmd.target}"
            }
            LeoProtocol.Action.FS_READ -> {
                val content = fileEngine.readFileContent(cmd.target)
                Logger.file("[FS] Read: ${cmd.target} (${content.length} bytes)")
                content.take(2000)  // Cap to prevent context explosion
            }
            LeoProtocol.Action.FS_DELETE -> {
                fileEngine.deleteTarget(cmd.target, cmd.value)
                "Deleted: ${cmd.target}"
            }
            LeoProtocol.Action.FS_MKDIR -> {
                fileEngine.createDirectory(cmd.target)
                Logger.file("[FS] Directory created: ${cmd.target}")
                "Directory created: ${cmd.target}"
            }
            else -> "Unknown FS action"
        }
    }

    private fun executeGitAction(cmd: CommandParser.LeoCommand): String {
        return when (cmd.action) {
            LeoProtocol.Action.GIT_INIT  -> gitManager.initAndLink(cmd.target, cmd.value)
            LeoProtocol.Action.GIT_PUSH  -> gitManager.pushAll(cmd.target, cmd.value)
            LeoProtocol.Action.GIT_CLONE -> gitManager.cloneRepo(cmd.target, cmd.value)
            else -> "Unknown Git action"
        }
    }

    private fun executeShellAction(cmd: CommandParser.LeoCommand): String {
        val output = shellBridge.exec(cmd.value)
        Logger.action("[Shell] → ${cmd.value}\n$output")
        return output.take(1000)  // Cap shell output
    }
}
