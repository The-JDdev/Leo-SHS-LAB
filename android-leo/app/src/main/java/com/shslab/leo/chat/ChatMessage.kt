package com.shslab.leo.chat

import java.util.UUID

enum class MessageRole { USER, LEO }

/**
 * ══════════════════════════════════════════
 *  LEO CHAT MESSAGE MODEL — SHS LAB
 *
 *  Single message in the chat stream.
 *  Leo messages carry a mutable thinking-log list
 *  that accumulates system events during execution.
 * ══════════════════════════════════════════
 */
data class ChatMessage(
    val id: String               = UUID.randomUUID().toString(),
    val role: MessageRole,
    @Volatile var text: String   = "",
    val thinkingLogs: MutableList<String> = mutableListOf(),
    val timestamp: Long          = System.currentTimeMillis(),
    @Volatile var isPending: Boolean = false,
    @Volatile var isThinkingExpanded: Boolean = false
)
