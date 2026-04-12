package com.shslab.leo.network

import com.shslab.leo.core.LeoProtocol
import com.shslab.leo.core.Logger
import com.shslab.leo.security.SecurityManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════
 *  LEO NEURAL LINK — DIRECT NETWORK ENGINE
 *  SHS LAB
 *
 *  Memory-efficient OkHttp client.
 *  All credentials fetched live from SecurityManager.
 *  Background coroutine-safe (call from IO dispatcher).
 * ══════════════════════════════════════════
 */
class LeoNetworkClient {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // Shared client — single instance, connection pool saves memory
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(2, 30, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    // Conversation history — bounded to 10 turns to prevent memory bloat
    private val conversationHistory = ArrayDeque<Map<String, String>>(20)
    private val MAX_HISTORY = 10

    /**
     * REACT MODE: Send full conversation history to the AI.
     * The caller builds and manages conversation history — including
     * the system prompt (already in history[0]).
     *
     * This is the primary send method for the ReAct loop.
     */
    fun sendWithHistory(history: List<Map<String, String>>): String {
        val apiKey   = SecurityManager.getActiveApiKey()
        val endpoint = SecurityManager.getActiveEndpoint()
        val model    = SecurityManager.getActiveModel()
        val provider = SecurityManager.getActiveProvider()

        if (apiKey.isBlank()) throw IllegalStateException("API key not configured for provider: $provider")

        Logger.net("[Leo→AI] Uplink: $provider | model: $model | turns: ${history.size}")

        val body    = buildRequestBody(model, history, provider)
        val request = buildRequest(endpoint, apiKey, body, provider)

        Logger.net("[Leo→AI] Transmitting ${body.contentLength()} bytes...")

        val responseJson = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw RuntimeException("HTTP ${response.code}: ${errBody.take(500)}")
            }
            response.body?.string() ?: throw RuntimeException("Empty response body")
        }

        val content = extractContent(responseJson, provider)
        Logger.net("[Leo←AI] Response: ${content.take(120)}")
        return content
    }

    /**
     * LEGACY: Send a single prompt (builds payload internally).
     * Still used for boot greeting and simple non-ReAct calls.
     */
    fun sendPrompt(userMessage: String): String {
        val apiKey   = SecurityManager.getActiveApiKey()
        val endpoint = SecurityManager.getActiveEndpoint()
        val model    = SecurityManager.getActiveModel()
        val provider = SecurityManager.getActiveProvider()

        if (apiKey.isBlank()) {
            Logger.error("No API key configured. Set one in the vault.")
            throw IllegalStateException("API key not configured for provider: $provider")
        }

        Logger.net("[Leo]: Establishing direct uplink → $provider")

        val messages = LeoProtocol.buildReActPayload(conversationHistory.toList() + listOf(mapOf("role" to "user", "content" to userMessage)))
        val body     = buildRequestBody(model, messages, provider)
        val request  = buildRequest(endpoint, apiKey, body, provider)

        Logger.net("[Leo]: Transmitting payload (${body.contentLength()} bytes)...")

        val responseJson = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw RuntimeException("HTTP ${response.code}: $errBody")
            }
            response.body?.string() ?: throw RuntimeException("Empty response body")
        }

        val content = extractContent(responseJson, provider)
        Logger.net("[Leo]: Payload received (${content.length} chars)")

        // Update conversation history
        appendHistory("user", userMessage)
        appendHistory("assistant", content)

        return content
    }

    private fun buildRequestBody(
        model: String,
        messages: List<Map<String, String>>,
        provider: String
    ): okhttp3.RequestBody {
        val json = when (provider) {
            "claude" -> {
                // Anthropic format: system separate, messages array without system
                val systemMsg = messages.firstOrNull { it["role"] == "system" }?.get("content") ?: ""
                val userMsgs  = messages.filter { it["role"] != "system" }
                val msgsArray = JSONArray()
                userMsgs.forEach { m ->
                    msgsArray.put(JSONObject().apply {
                        put("role", m["role"])
                        put("content", m["content"])
                    })
                }
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 1024)
                    put("system", systemMsg)
                    put("messages", msgsArray)
                }.toString()
            }
            else -> {
                // OpenAI-compatible format (OpenRouter, Gemini via proxy, OpenAI)
                val msgsArray = JSONArray()
                messages.forEach { m ->
                    msgsArray.put(JSONObject().apply {
                        put("role", m["role"])
                        put("content", m["content"])
                    })
                }
                JSONObject().apply {
                    put("model", model)
                    put("messages", msgsArray)
                    put("max_tokens", 1024)
                    put("temperature", 0.2)  // Low temp for deterministic JSON output
                }.toString()
            }
        }
        return json.toRequestBody(JSON_MEDIA)
    }

    private fun buildRequest(
        endpoint: String,
        apiKey: String,
        body: okhttp3.RequestBody,
        provider: String
    ): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .post(body)

        when (provider) {
            "claude" -> {
                builder.addHeader("x-api-key", apiKey)
                builder.addHeader("anthropic-version", "2023-06-01")
                builder.addHeader("Content-Type", "application/json")
            }
            else -> {
                builder.addHeader("Authorization", "Bearer $apiKey")
                builder.addHeader("Content-Type", "application/json")
            }
        }

        return builder.build()
    }

    private fun extractContent(responseJson: String, provider: String): String {
        return try {
            val obj = JSONObject(responseJson)
            when (provider) {
                "claude" -> {
                    obj.getJSONArray("content")
                        .getJSONObject(0)
                        .getString("text")
                }
                else -> {
                    obj.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }
            }
        } catch (e: Exception) {
            Logger.error("Content extraction failed: ${e.message}")
            responseJson  // Return raw if parse fails
        }
    }

    private fun appendHistory(role: String, content: String) {
        if (conversationHistory.size >= MAX_HISTORY * 2) {
            // Remove oldest pair (user + assistant)
            repeat(2) { if (conversationHistory.isNotEmpty()) conversationHistory.removeFirst() }
        }
        conversationHistory.addLast(mapOf("role" to role, "content" to content))
    }

    fun clearHistory() {
        conversationHistory.clear()
        Logger.system("Conversation history cleared.")
    }
}
