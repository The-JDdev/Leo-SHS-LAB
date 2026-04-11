package com.shslab.leo.parser

import com.shslab.leo.core.Logger
import org.json.JSONObject

/**
 * ══════════════════════════════════════════
 *  LEO COMMAND PARSER — SHS LAB
 *
 *  Strips markdown, extracts valid JSON.
 *  Validates against Leo command schema.
 * ══════════════════════════════════════════
 */
object CommandParser {

    // Regex: extract JSON object even if wrapped in markdown code blocks
    private val JSON_BLOCK_REGEX = Regex(
        "```(?:json)?\\s*([\\s\\S]*?)```|\\{[\\s\\S]*\\}",
        setOf(RegexOption.MULTILINE)
    )

    // Simple JSON object detection
    private val JSON_OBJECT_REGEX = Regex("\\{[\\s\\S]*\\}", RegexOption.DOT_MATCHES_ALL)

    data class LeoCommand(
        val action: String,
        val target: String,
        val value: String,
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

        // Extract JSON from possible markdown wrapper
        val jsonString = extractJson(trimmed) ?: run {
            Logger.error("Parser: no valid JSON found in response")
            Logger.raw("Raw response: ${trimmed.take(200)}")
            return null
        }

        return try {
            val obj    = JSONObject(jsonString)
            val action = obj.optString("action", "").uppercase()
            val target = obj.optString("target", "")
            val value  = obj.optString("value", "")

            if (action.isEmpty()) {
                Logger.error("Parser: missing 'action' field")
                return null
            }

            // Extract optional meta
            val meta     = obj.optJSONObject("meta")
            val priority = meta?.optInt("priority", 1) ?: 1
            val delayMs  = meta?.optLong("delay_ms", 0L) ?: 0L

            val cmd = LeoCommand(action, target, value, priority, delayMs, obj)
            Logger.action("[Leo]: Dispatching Action → $action | target='$target'")
            cmd
        } catch (e: Exception) {
            Logger.error("Parser JSON exception: ${e.message}")
            null
        }
    }

    private fun extractJson(input: String): String? {
        // Case 1: Clean JSON object directly
        if (input.startsWith("{")) {
            return cleanJsonString(input)
        }

        // Case 2: Markdown code block ```json ... ```
        val blockMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(input)
        if (blockMatch != null) {
            val inner = blockMatch.groupValues[1].trim()
            if (inner.startsWith("{")) return cleanJsonString(inner)
        }

        // Case 3: JSON object embedded in prose
        val objectMatch = JSON_OBJECT_REGEX.find(input)
        if (objectMatch != null) {
            return cleanJsonString(objectMatch.value)
        }

        return null
    }

    /**
     * Remove common AI response artifacts from JSON strings
     */
    private fun cleanJsonString(raw: String): String {
        return raw
            .trimIndent()
            .trim()
            .replace("\u200B", "")   // Zero-width space
            .replace("\uFEFF", "")   // BOM
    }

    /**
     * Build a structured feedback JSON for the next AI call.
     * Closes the internal feedback loop (Phase 2).
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
