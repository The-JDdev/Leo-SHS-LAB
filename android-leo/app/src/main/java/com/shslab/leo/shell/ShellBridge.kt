package com.shslab.leo.shell

import com.shslab.leo.core.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ══════════════════════════════════════════
 *  LEO SHELL BRIDGE — IN-APP SHELL EXECUTOR
 *  SHS LAB
 *
 *  ProcessBuilder wrapper for raw Linux/Android
 *  shell command execution.
 *  Captures both stdout and stderr.
 *  Returns structured output back to ActionExecutor.
 * ══════════════════════════════════════════
 */
class ShellBridge {

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val success: Boolean = exitCode == 0
    )

    companion object {
        private const val CMD_TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT_CHARS = 4096
    }

    /**
     * Execute a shell command and return combined output string.
     * Convenience wrapper for single-string return.
     */
    fun exec(command: String): String {
        return execWithExitCode(command).output
    }

    /**
     * Execute a shell command and return full ShellResult.
     * Captures stdout and stderr separately, then merges.
     */
    fun execWithExitCode(command: String): ShellResult {
        Logger.action("[Shell] Executing: $command")

        return try {
            val process = ProcessBuilder()
                .apply {
                    // Split on spaces but respect quoted strings
                    command("sh", "-c", command)
                    redirectErrorStream(false)  // Capture stdout/stderr separately
                    environment()["HOME"]    = "/sdcard"
                    environment()["TMPDIR"]  = "/sdcard/.tmp"
                }
                .start()

            // Read stdout
            val stdoutThread = StringBuilder()
            val stdoutReader = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutThread.appendLine(line)
                            // Memory guard: cap output
                            if (stdoutThread.length > MAX_OUTPUT_CHARS) return@use
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // Read stderr
            val stderrThread = StringBuilder()
            val stderrReader = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrThread.appendLine(line)
                            if (stderrThread.length > MAX_OUTPUT_CHARS / 2) return@use
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // Wait with timeout
            val finished = runCatching {
                val deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS
                while (process.isAlive && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                if (process.isAlive) {
                    process.destroyForcibly()
                    Logger.warn("[Shell] Command timed out, killed: $command")
                }
            }

            stdoutReader.join(500)
            stderrReader.join(500)

            val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
            val stdout   = stdoutThread.toString().trim()
            val stderr   = stderrThread.toString().trim()

            val combined = buildString {
                if (stdout.isNotEmpty()) append(stdout)
                if (stderr.isNotEmpty()) {
                    if (stdout.isNotEmpty()) appendLine()
                    append("[STDERR] $stderr")
                }
            }.take(MAX_OUTPUT_CHARS)

            Logger.action("[Shell] Exit:$exitCode | Output: ${combined.take(100)}...")

            ShellResult(combined, exitCode)

        } catch (e: Exception) {
            Logger.error("[Shell] ProcessBuilder failed: ${e.message}")
            ShellResult("ERROR: ${e.message}", -1)
        }
    }

    /**
     * Execute multiple commands in sequence.
     * Stops on first non-zero exit code.
     */
    fun execSequence(vararg commands: String): ShellResult {
        var lastResult = ShellResult("", 0)
        for (cmd in commands) {
            lastResult = execWithExitCode(cmd)
            if (!lastResult.success) {
                Logger.error("[Shell] Sequence aborted at: $cmd")
                break
            }
        }
        return lastResult
    }
}
