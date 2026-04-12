package com.shslab.leo.shell

import android.content.Context
import com.shslab.leo.core.Logger
import com.shslab.leo.security.SecurityManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * ══════════════════════════════════════════════════════
 *  LEO SHELL BRIDGE — SUPREME SOVEREIGN v6
 *  SHS LAB — TERMINAL-FIRST EXECUTION ENGINE
 *
 *  Terminal-First Architecture:
 *  - All file ops: echo, cat, cp, mv, zip → SHELL_EXEC
 *  - GitHub operations: curl REST API → SHELL_EXEC
 *  - GitHub token auto-injected from SecurityManager vault
 *  - Token cached to internal app storage for shell access
 *
 *  Enhancements over v5:
 *  - GitHub token auto-injection into curl commands
 *  - GitHub convenience methods (createRepo, uploadFile, release)
 *  - Longer timeout for curl downloads (60s)
 *  - Extended output capture (8KB)
 *  - Environment PATH expanded for system commands
 * ══════════════════════════════════════════════════════
 */
class ShellBridge(private val context: Context? = null) {

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val success: Boolean = exitCode == 0
    )

    companion object {
        private const val CMD_TIMEOUT_MS  = 60_000L   // 60s for curl downloads/uploads
        private const val MAX_OUTPUT_CHARS = 8192      // 8KB — captures full GitHub API responses
    }

    // ══════════════════════════════════════════════════
    //  PRIMARY EXEC — used by ActionExecutor
    // ══════════════════════════════════════════════════

    /**
     * Execute a shell command. Auto-injects GitHub token if
     * the command is a curl call to api.github.com and the
     * placeholder GITHUB_TOKEN is detected.
     */
    fun exec(command: String): String {
        val processedCmd = preprocessCommand(command)
        return execWithExitCode(processedCmd).output
    }

    fun execWithExitCode(command: String): ShellResult {
        Logger.action("[Shell] Exec: ${command.take(200)}")

        return try {
            val process = ProcessBuilder()
                .apply {
                    command("sh", "-c", command)
                    redirectErrorStream(false)
                    // Expand PATH so system commands are found
                    environment()["HOME"]    = "/sdcard"
                    environment()["TMPDIR"]  = "/sdcard/.tmp"
                    environment()["PATH"]    = "/system/bin:/system/xbin:/vendor/bin:${System.getenv("PATH") ?: ""}"
                    environment()["TERM"]    = "xterm"
                }
                .start()

            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()

            val stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuf.appendLine(line)
                            if (stdoutBuf.length > MAX_OUTPUT_CHARS) return@use
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            val stderrThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrBuf.appendLine(line)
                            if (stderrBuf.length > MAX_OUTPUT_CHARS / 2) return@use
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // Wait with timeout
            val deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (process.isAlive) {
                process.destroyForcibly()
                Logger.warn("[Shell] Timeout — killed: ${command.take(80)}")
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
            val stdout   = stdoutBuf.toString().trim()
            val stderr   = stderrBuf.toString().trim()

            val combined = buildString {
                if (stdout.isNotEmpty()) append(stdout)
                if (stderr.isNotEmpty()) {
                    if (stdout.isNotEmpty()) appendLine()
                    append("[STDERR] $stderr")
                }
            }.take(MAX_OUTPUT_CHARS)

            val statusLine = "exit:$exitCode | ${combined.take(80)}"
            Logger.action("[Shell] $statusLine")

            ShellResult(combined.ifEmpty { "exit:$exitCode" }, exitCode)

        } catch (e: Exception) {
            Logger.error("[Shell] ProcessBuilder failed: ${e.message}")
            ShellResult("ERROR: ${e.message}", -1)
        }
    }

    fun execSequence(vararg commands: String): ShellResult {
        var last = ShellResult("", 0)
        for (cmd in commands) {
            last = execWithExitCode(cmd)
            if (!last.success) {
                Logger.error("[Shell] Sequence aborted: $cmd")
                break
            }
        }
        return last
    }

    // ══════════════════════════════════════════════════
    //  COMMAND PREPROCESSOR
    //  Auto-injects GitHub token into curl commands
    // ══════════════════════════════════════════════════

    private fun preprocessCommand(command: String): String {
        var cmd = command

        // If command is a GitHub API curl call and uses the GITHUB_TOKEN placeholder
        if (cmd.contains("api.github.com") && cmd.contains("GITHUB_TOKEN")) {
            val token = resolveGitHubToken()
            if (token.isNotEmpty()) {
                cmd = cmd.replace("GITHUB_TOKEN", token)
                    .replace("\$GITHUB_TOKEN", token)
                    .replace("\${GITHUB_TOKEN}", token)
                Logger.action("[Shell] GitHub token injected into curl command")
            } else {
                Logger.warn("[Shell] No GitHub token available — command may fail")
            }
        }

        // If cat-ing the token file path, provide real token
        if (cmd.contains("github_token.txt") && cmd.contains("cat")) {
            val token = resolveGitHubToken()
            if (token.isNotEmpty()) {
                // Cache token to file for shell access
                cacheTokenToFile(token)
            }
        }

        return cmd
    }

    // ══════════════════════════════════════════════════
    //  GITHUB TOKEN RESOLUTION
    //  Reads from SecurityManager → caches to internal file
    // ══════════════════════════════════════════════════

    private fun resolveGitHubToken(): String {
        // 1. Try SecurityManager (primary source — set in Vault)
        if (context != null) {
            val token = SecurityManager.getGitHubToken()
            if (token.isNotEmpty()) {
                cacheTokenToFile(token)
                return token
            }
        }
        // 2. Try internal cached file
        return try {
            val tokenFile = File(context?.filesDir, "github_token.txt")
            if (tokenFile.exists()) tokenFile.readText().trim() else ""
        } catch (_: Exception) { "" }
    }

    private fun cacheTokenToFile(token: String) {
        try {
            val dir = context?.filesDir ?: return
            File(dir, "github_token.txt").writeText(token)
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════
    //  GITHUB CONVENIENCE METHODS
    //  Called by ActionExecutor for GIT_* actions
    //  All use curl REST API — no gh CLI needed
    // ══════════════════════════════════════════════════

    /**
     * Create a GitHub repository via REST API
     */
    fun githubCreateRepo(name: String, private_: Boolean = false, description: String = ""): String {
        val token = resolveGitHubToken()
        if (token.isEmpty()) return "ERROR: No GitHub token in vault"
        val cmd = """curl -s -X POST https://api.github.com/user/repos \
            -H "Authorization: Bearer $token" \
            -H "Content-Type: application/json" \
            -d '{"name":"$name","private":$private_,"description":"$description","auto_init":false}'"""
        return exec(cmd)
    }

    /**
     * Upload a file to GitHub via Contents API
     */
    fun githubUploadFile(
        owner: String,
        repo: String,
        path: String,
        localFilePath: String,
        message: String = "upload via Leo"
    ): String {
        val token = resolveGitHubToken()
        if (token.isEmpty()) return "ERROR: No GitHub token in vault"
        val d = "${'$'}"  // dollar sign for shell variables
        val encodedCmd = """
            B64=${d}(base64 -w0 "$localFilePath")
            EXISTING=${d}(curl -s -H "Authorization: Bearer $token" "https://api.github.com/repos/$owner/$repo/contents/$path")
            SHA=${d}(echo "${d}EXISTING" | grep -o '"sha":"[^"]*"' | head -1 | cut -d'"' -f4)
            if [ -n "${d}SHA" ]; then
                curl -s -X PUT "https://api.github.com/repos/$owner/$repo/contents/$path" \
                    -H "Authorization: Bearer $token" \
                    -H "Content-Type: application/json" \
                    -d "{\"message\":\"$message\",\"content\":\"${d}B64\",\"sha\":\"${d}SHA\"}"
            else
                curl -s -X PUT "https://api.github.com/repos/$owner/$repo/contents/$path" \
                    -H "Authorization: Bearer $token" \
                    -H "Content-Type: application/json" \
                    -d "{\"message\":\"$message\",\"content\":\"${d}B64\"}"
            fi
        """.trimIndent()
        return exec(encodedCmd)
    }

    /**
     * Check if gh CLI is available, return path or empty
     */
    fun findGhCli(): String {
        val result = exec("which gh 2>/dev/null || command -v gh 2>/dev/null")
        return result.trim()
    }

    /**
     * git config setup for a repo
     */
    fun gitConfigSetup(
        repoPath: String,
        username: String,
        email: String,
        token: String = ""
    ): String {
        val t = token.ifEmpty { resolveGitHubToken() }
        return exec("""
            cd "$repoPath" && \
            git config user.name "$username" && \
            git config user.email "$email" && \
            echo "git_config_done"
        """.trimIndent())
    }

    /**
     * Full git commit and push with token-based auth
     */
    fun gitCommitAndPush(
        repoPath: String,
        message: String,
        remote: String = "origin"
    ): String {
        return exec("""
            cd "$repoPath" && \
            git add -A && \
            git commit -m "$message" && \
            git push "$remote" HEAD:main 2>&1
        """.trimIndent())
    }
}
