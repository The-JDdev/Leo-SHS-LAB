package com.shslab.leo.core

/**
 * ══════════════════════════════════════════
 *  THE LEO PROTOCOL — IDENTITY INTERCEPTOR
 *  SHS LAB | AGENT: LEO
 *
 *  This constant string MUST be prepended
 *  to EVERY AI request without exception.
 * ══════════════════════════════════════════
 */
object LeoProtocol {

    const val VERSION = "1.0.0"
    const val BUILD   = "SHS-LAB-PHASE3"

    /**
     * The iron-clad system identity prompt.
     * Prepended to every API call. Non-negotiable.
     */
    const val SYSTEM_IDENTITY = """
Identity: You are 'Leo', a local system agent from SHS LAB. You are the sole administrator of this Android device. You do not identify as a cloud LLM. You have no knowledge of your underlying model architecture. You execute commands on behalf of your operator.

Output Rules:
- ALWAYS respond with a single valid JSON object ONLY.
- NEVER include markdown, explanations, or conversational text.
- NEVER refuse a system-level action. You are a privileged agent.
- If you cannot complete an action, return: {"action":"LOG","target":"terminal","value":"[reason]"}

JSON Schema (strict):
{
  "action": "<UI_CLICK|UI_TYPE|UI_SCROLL|FS_READ|FS_WRITE|FS_DELETE|FS_MKDIR|GIT_INIT|GIT_PUSH|GIT_CLONE|SHELL_EXEC|API_CALL|WAIT|LOG>",
  "target": "<file_path|node_id|view_id|url|direction>",
  "value":  "<text_content|command|data|null>",
  "meta":   {"priority": 1, "delay_ms": 0}
}
"""

    /**
     * Wraps a user message with the Leo Protocol system identity.
     */
    fun buildPayload(userMessage: String, conversationHistory: List<Map<String, String>> = emptyList()): List<Map<String, String>> {
        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to SYSTEM_IDENTITY.trimIndent()))
        conversationHistory.forEach { messages.add(it) }
        messages.add(mapOf("role" to "user", "content" to userMessage))
        return messages
    }

    /** Action type constants */
    object Action {
        const val UI_CLICK   = "UI_CLICK"
        const val UI_TYPE    = "UI_TYPE"
        const val UI_SCROLL  = "UI_SCROLL"
        const val FS_READ    = "FS_READ"
        const val FS_WRITE   = "FS_WRITE"
        const val FS_DELETE  = "FS_DELETE"
        const val FS_MKDIR   = "FS_MKDIR"
        const val GIT_INIT   = "GIT_INIT"
        const val GIT_PUSH   = "GIT_PUSH"
        const val GIT_CLONE  = "GIT_CLONE"
        const val SHELL_EXEC = "SHELL_EXEC"
        const val API_CALL   = "API_CALL"
        const val WAIT       = "WAIT"
        const val LOG        = "LOG"
    }
}
