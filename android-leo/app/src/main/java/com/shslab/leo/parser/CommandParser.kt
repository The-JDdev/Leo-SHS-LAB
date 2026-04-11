package com.shslab.leo.parser

import com.shslab.leo.core.Logger
import org.json.JSONObject

/**
 * ══════════════════════════════════════════
 *  LEO COMMAND PARSER — SHS LAB v3
 *
 *  Strips markdown, extracts valid JSON.
 *  Validates against Leo command schema.
 *  Supports sub_action and timeout_ms for WAIT_FOR.
 * ══════════════════════════════════════════
 */
object CommandParser {

    private val JSON_OBJECT_REGEX = Regex("\\{[\\s\\S]*\\}", RegexOption.DOT_MATCHES_ALL)

    data class LeoCommand(
        val action: String,
        val subAction: String,
        val target: String,
        val value: String,
        val timeoutMs: Long,
        val priority: Int = 1,
        val delayMs: Long = 0L,
        val raw: JSONObject
    )

    /**
     * Parse AI response string into a LeoCommand.
     * @return LeoCommand or null if invalid/unparseable
     */
    fun parse(aiResponse: String): LeoCommand? {
        Logger.net("[Leo]: Parsing AI Payload...")

        val trimmed = aiResponse.trim()
        val jsonString = extractJson(trimmed) ?: run {
            Logger.error("Parser: no valid JSON found in response")
            Logger.raw("Raw response: ${trimmed.take(200)}")
            return null
        }

        return try {
            val obj       = JSONObject(jsonString)
            val action    = obj.optString("action", "").uppercase()
            val subAction = obj.optString("sub_action", "").uppercase()
            val target    = obj.optString("target", "")
            val value     = obj.optString("value", "")
            val timeoutMs = obj.optLong("timeout_ms", 10000L)

            if (action.isEmpty()) {
                Logger.error("Parser: missing 'action' field")
                return null
            }

            val meta      = obj.optJSONObject("meta")
            val priority  = meta?.optInt("priority", 1) ?: 1
            val delayMs   = meta?.optLong("delay_ms", 0L) ?: 0L

            val cmd = LeoCommand(action, subAction, target, value, timeoutMs, priority, delayMs, obj)
            Logger.action("[Leo]: Dispatching → $action${if (subAction.isNotEmpty()) ":$subAction" else ""} | target='$target'")
            cmd
        } catch (e: Exception) {
            Logger.error("Parser JSON exception: ${e.message}")
            null
        }
    }

    private fun extractJson(input: String): String? {
        if (input.startsWith("{")) return cleanJsonString(input)

        val blockMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(input)
        if (blockMatch != null) {
            val inner = blockMatch.groupValues[1].trim()
            if (inner.startsWith("{")) return cleanJsonString(inner)
        }

        val objectMatch = JSON_OBJECT_REGEX.find(input)
        if (objectMatch != null) return cleanJsonString(objectMatch.value)

        return null
    }

    private fun cleanJsonString(raw: String): String {
        return raw
            .trimIndent()
            .trim()
            .replace("\u200B", "")
            .replace("\uFEFF", "")
    }

    /**
     * True if this response is a LOG action (final conversational reply).
     */
    fun isLogAction(aiResponse: String): Boolean {
        val json = extractJson(aiResponse.trim()) ?: return false
        return try {
            JSONObject(json).optString("action", "").uppercase() == "LOG"
        } catch (_: Exception) { false }
    }

    /**
     * Extract the human-readable display text from an AI response.
     * Returns LOG value if action=LOG, otherwise a short action summary.
     */
    fun extractDisplayText(aiResponse: String): String {
        val trimmed   = aiResponse.trim()
        val jsonString = extractJson(trimmed) ?: return trimmed.take(500)
        return try {
            val obj    = JSONObject(jsonString)
            val action = obj.optString("action", "").uppercase()
            val value  = obj.optString("value", "")
            val target = obj.optString("target", "")
            when {
                action == "LOG"  -> value.ifBlank { "(no message)" }
                action.isNotEmpty() && target.isNotEmpty() -> "⚡ $action → $target"
                action.isNotEmpty() -> "⚡ $action executed"
                else -> trimmed.take(500)
            }
        } catch (_: Exception) {
            trimmed.take(500)
        }
    }

    /**
     * Build a structured feedback JSON for the mission reporting loop.
     */
    fun buildFeedback(status: String, action: String, message: String): String {
        return JSONObject().apply {
            put("status", status)
            put("action", action)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
}
