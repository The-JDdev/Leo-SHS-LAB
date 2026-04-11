package com.shslab.leo.parser

import com.shslab.leo.core.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * ══════════════════════════════════════════
 *  LEO COMMAND PARSER — SHS LAB v4
 *  Absolute Sovereignty Edition
 *
 *  Supports BOTH formats:
 *  • Single JSON object: {"action":"LOG","value":"done"}
 *  • JSON array:  [{"action":"UI_CONTROL",...}, {"action":"LOG",...}]
 *
 *  parseMulti() is the primary entry point.
 * ══════════════════════════════════════════
 */
object CommandParser {

    private val JSON_OBJECT_REGEX = Regex("\\{[\\s\\S]*\\}", RegexOption.DOT_MATCHES_ALL)
    private val JSON_ARRAY_REGEX  = Regex("\\[[\\s\\S]*\\]",  RegexOption.DOT_MATCHES_ALL)

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

    // ══════════════════════════════════════════
    //  PRIMARY: Parse array OR single object
    // ══════════════════════════════════════════

    /**
     * Primary parser — handles both JSON arrays and single JSON objects.
     * This is the ONLY entry point called by ActionExecutor.
     *
     * @return List of LeoCommands in execution order, or empty list on failure
     */
    fun parseMulti(aiResponse: String): List<LeoCommand> {
        val trimmed = aiResponse.trim()
        Logger.net("[Parser]: Parsing AI response (${trimmed.length} chars)...")

        // Extract raw JSON (strips markdown, prose, etc.)
        val jsonStr = extractAnyJson(trimmed) ?: run {
            Logger.error("[Parser]: No valid JSON found")
            Logger.raw("Raw: ${trimmed.take(200)}")
            return emptyList()
        }

        return when {
            jsonStr.startsWith("[") -> parseJsonArray(jsonStr)
            jsonStr.startsWith("{") -> {
                val cmd = parseObject(JSONObject(jsonStr))
                if (cmd != null) listOf(cmd) else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun parseJsonArray(jsonStr: String): List<LeoCommand> {
        return try {
            val arr = JSONArray(jsonStr)
            val commands = mutableListOf<LeoCommand>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val cmd = parseObject(obj)
                if (cmd != null) {
                    commands.add(cmd)
                    Logger.action("[Parser]: Step ${i+1} → ${cmd.action}${if (cmd.subAction.isNotEmpty()) ":${cmd.subAction}" else ""}")
                }
            }
            Logger.action("[Parser]: ${commands.size} commands in array")
            commands
        } catch (e: Exception) {
            Logger.error("[Parser]: Array parse failed: ${e.message}")
            emptyList()
        }
    }

    internal fun parseObject(obj: JSONObject): LeoCommand? {
        return try {
            val action    = obj.optString("action", "").uppercase().trim()
            val subAction = obj.optString("sub_action", "").uppercase().trim()
            val target    = obj.optString("target", "").trim()
            val value     = obj.optString("value", obj.optString("message", "")).trim()
            val timeoutMs = obj.optLong("timeout_ms", 10000L)

            if (action.isEmpty()) {
                Logger.error("[Parser]: Missing 'action' field in object")
                return null
            }

            val meta     = obj.optJSONObject("meta")
            val priority = meta?.optInt("priority", 1) ?: 1
            val delayMs  = meta?.optLong("delay_ms", 0L) ?: 0L

            LeoCommand(action, subAction, target, value, timeoutMs, priority, delayMs, obj)
        } catch (e: Exception) {
            Logger.error("[Parser]: Object parse failed: ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════════
    //  JSON EXTRACTION — strips markdown/prose
    // ══════════════════════════════════════════

    private fun extractAnyJson(input: String): String? {
        // Case 1: Direct JSON (array or object)
        if (input.startsWith("[") || input.startsWith("{")) {
            return cleanJson(input)
        }

        // Case 2: Markdown code block ```json [...] ``` or ```json {...} ```
        val blockMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(input)
        if (blockMatch != null) {
            val inner = blockMatch.groupValues[1].trim()
            if (inner.startsWith("[") || inner.startsWith("{")) return cleanJson(inner)
        }

        // Case 3: JSON array embedded in prose
        val arrayMatch = JSON_ARRAY_REGEX.find(input)
        if (arrayMatch != null) return cleanJson(arrayMatch.value)

        // Case 4: JSON object embedded in prose
        val objectMatch = JSON_OBJECT_REGEX.find(input)
        if (objectMatch != null) return cleanJson(objectMatch.value)

        return null
    }

    private fun cleanJson(raw: String): String = raw
        .trimIndent()
        .trim()
        .replace("\u200B", "")
        .replace("\uFEFF", "")

    // ══════════════════════════════════════════
    //  HELPERS for MainActivity
    // ══════════════════════════════════════════

    /**
     * True if the last command in the response is a LOG (mission done).
     * Used to detect mission completion without full parse.
     */
    fun isLogAction(aiResponse: String): Boolean {
        val cmds = parseMulti(aiResponse)
        return cmds.isNotEmpty() && cmds.last().action == "LOG"
    }

    /**
     * Backward-compatible single-object parse.
     */
    fun parse(aiResponse: String): LeoCommand? = parseMulti(aiResponse).firstOrNull()

    /**
     * Extract the display text — returns the LOG value from the last LOG command in the array.
     */
    fun extractDisplayText(aiResponse: String): String {
        val cmds = parseMulti(aiResponse)
        val log  = cmds.lastOrNull { it.action == "LOG" }
        return when {
            log != null && log.value.isNotBlank() -> log.value
            cmds.isNotEmpty() -> "⚡ ${cmds.size} action${if (cmds.size > 1) "s" else ""} planned"
            else -> aiResponse.trim().take(500)
        }
    }

    fun buildFeedback(status: String, action: String, message: String): String {
        return JSONObject().apply {
            put("status", status)
            put("action", action)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    fun raw(text: String) = Logger.raw(text)
}
