package com.shslab.leo.git

import android.content.Context
import com.shslab.leo.core.Logger
import com.shslab.leo.security.SecurityManager
import com.shslab.leo.shell.ShellBridge
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════
 *  LEO GIT MANAGER — GITHUB AUTOMATION
 *  SHS LAB
 *
 *  - GitHub REST API: check/create repos
 *  - ShellBridge: run git commands natively
 *  - Token loaded live from SecurityManager
 * ══════════════════════════════════════════
 */
class GitManager(private val context: Context) {

    private val shell = ShellBridge()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val GITHUB_API = "https://api.github.com"
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    // ════════════════════════════════════════════
    //  GITHUB REST API METHODS
    // ════════════════════════════════════════════

    /**
     * Check if a GitHub repository exists for the authenticated user.
     * @param repoName e.g. "my-project"
     * @return true if exists
     */
    fun repoExists(repoName: String): Boolean {
        val token = SecurityManager.getGitHubToken()
        if (token.isBlank()) { Logger.warn("[GIT] No GitHub token configured"); return false }

        val username = getAuthenticatedUsername(token) ?: return false
        val request = Request.Builder()
            .url("$GITHUB_API/repos/$username/$repoName")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { it.code == 200 }
        } catch (e: Exception) {
            Logger.error("[GIT] repoExists check failed: ${e.message}")
            false
        }
    }

    /**
     * Create a new GitHub repository dynamically.
     * @param repoName name of repo to create
     * @param description optional description
     * @param private whether the repo should be private
     * @return URL of created repo or null on failure
     */
    fun createRepo(repoName: String, description: String = "", private: Boolean = false): String? {
        val token = SecurityManager.getGitHubToken()
        if (token.isBlank()) { Logger.error("[GIT] No GitHub token — cannot create repo"); return null }

        val body = JSONObject().apply {
            put("name", repoName)
            put("description", description.ifEmpty { "Auto-created by Leo — SHS LAB" })
            put("private", private)
            put("auto_init", true)
        }.toString().toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url("$GITHUB_API/user/repos")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .post(body)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (response.code == 201) {
                    val url = json.optString("html_url")
                    Logger.git("[GIT] Repository created: $url")
                    url
                } else {
                    Logger.error("[GIT] Create repo failed: ${json.optString("message")}")
                    null
                }
            }
        } catch (e: Exception) {
            Logger.error("[GIT] createRepo exception: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════
    //  LOCAL GIT SHELL COMMANDS
    // ════════════════════════════════════════════

    /**
     * Initialize a local git repo and link it to a GitHub remote.
     * @param localPath local directory path
     * @param repoName GitHub repo name (auto-created if needed)
     */
    fun initAndLink(localPath: String, repoName: String): String {
        val token    = SecurityManager.getGitHubToken()
        val username = getAuthenticatedUsername(token) ?: "unknown"

        Logger.git("[GIT] Initializing repo at: $localPath")

        val commands = listOf(
            "git -C $localPath init",
            "git -C $localPath config user.email 'leo@shslab.ai'",
            "git -C $localPath config user.name 'Leo SHS LAB'"
        )

        val results = mutableListOf<String>()
        commands.forEach { cmd ->
            val out = shell.exec(cmd)
            results.add(out)
            Logger.git("[GIT] $cmd → $out")
        }

        // Create repo if it doesn't exist
        if (!repoExists(repoName)) {
            createRepo(repoName)
        }

        val remoteUrl = "https://$token@github.com/$username/$repoName.git"
        val remoteResult = shell.exec("git -C $localPath remote add origin $remoteUrl 2>&1 || git -C $localPath remote set-url origin $remoteUrl")
        Logger.git("[GIT] Remote linked: $remoteUrl → $remoteResult")

        return "Init complete: $localPath → github.com/$username/$repoName"
    }

    /**
     * Stage all files, commit, and push to origin/main.
     * @param localPath local directory
     * @param commitMessage commit message
     * @return "success" or error details
     */
    fun pushAll(localPath: String, commitMessage: String = "Auto-Sync by Leo"): String {
        Logger.git("[GIT] Pushing: $localPath")

        val steps = mapOf(
            "Stage all"  to "git -C $localPath add .",
            "Commit"     to "git -C $localPath commit -m \"$commitMessage\" --allow-empty",
            "Push"       to "git -C $localPath push -u origin main 2>&1 || git -C $localPath push -u origin master 2>&1"
        )

        val sb = StringBuilder()
        var exitCode = 0
        for ((label, cmd) in steps) {
            val result = shell.execWithExitCode(cmd)
            Logger.git("[GIT] $label: ${result.output}")
            sb.appendLine("[$label]: ${result.output}")
            if (result.exitCode != 0 && label == "Push") {
                exitCode = result.exitCode
            }
        }

        return if (exitCode == 0) "push_success" else "push_failed:${sb.toString().take(200)}"
    }

    /**
     * Clone a remote repository to a local path.
     */
    fun cloneRepo(repoUrl: String, localPath: String): String {
        Logger.git("[GIT] Cloning: $repoUrl → $localPath")
        val result = shell.exec("git clone $repoUrl $localPath 2>&1")
        Logger.git("[GIT] Clone result: $result")
        return result
    }

    // ── Private helpers ──

    private fun getAuthenticatedUsername(token: String): String? {
        if (token.isBlank()) return null
        return try {
            val request = Request.Builder()
                .url("$GITHUB_API/user")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                JSONObject(response.body?.string() ?: "{}").optString("login")
            }
        } catch (e: Exception) {
            Logger.error("[GIT] Auth check failed: ${e.message}")
            null
        }
    }
}
