package com.shslab.leo.executor

import com.shslab.leo.core.Logger
import com.shslab.leo.parser.CommandParser
import kotlinx.coroutines.delay
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ══════════════════════════════════════════
 *  LEO COMMAND QUEUE — 2GB RAM THROTTLE
 *  SHS LAB
 *
 *  Priority queue with mandatory inter-action
 *  delay to prevent CPU/RAM chokepoint.
 *  500ms minimum between heavy system actions.
 * ══════════════════════════════════════════
 */
class CommandQueue {

    companion object {
        /** Mandatory delay between heavy system actions (ms) */
        private const val MIN_ACTION_DELAY = 500L

        /** Maximum queued commands before rejection */
        private const val MAX_QUEUE_SIZE = 50
    }

    private val isKilled = AtomicBoolean(false)

    // Priority queue: lower priority number = higher priority
    private val queue = PriorityBlockingQueue<QueueEntry>(16, compareBy { it.priority })

    data class QueueEntry(
        val command: CommandParser.LeoCommand,
        val priority: Int,
        val enqueueTime: Long = System.currentTimeMillis()
    )

    /** Enqueue a command for execution */
    fun enqueue(command: CommandParser.LeoCommand): Boolean {
        if (isKilled.get()) {
            Logger.warn("Queue is killed — command rejected: ${command.action}")
            return false
        }
        if (queue.size >= MAX_QUEUE_SIZE) {
            Logger.warn("Queue full (${MAX_QUEUE_SIZE}) — dropping oldest")
            queue.poll()
        }
        queue.offer(QueueEntry(command, command.priority))
        Logger.action("Queued: ${command.action} (queue size: ${queue.size})")
        return true
    }

    /**
     * Drain and execute all queued commands via the provided executor.
     * Enforces minimum delay between actions.
     * Must be called from a coroutine (suspend).
     */
    suspend fun drainWith(executor: ActionExecutor) {
        var actionsRun = 0
        while (queue.isNotEmpty() && !isKilled.get()) {
            val entry = queue.poll() ?: break

            // Honor command-specific delay, minimum 500ms for heavy actions
            val waitMs = maxOf(entry.command.delayMs, MIN_ACTION_DELAY)
            if (actionsRun > 0) delay(waitMs)

            executor.dispatchSingle(entry.command)
            actionsRun++
        }

        if (actionsRun > 0) {
            // Aggressive cleanup after batch
            System.gc()
            Logger.system("[Leo]: Queue cleared, RAM freed. ($actionsRun actions executed)")
        }
    }

    fun kill() {
        isKilled.set(true)
        queue.clear()
        System.gc()
        Logger.warn("Command queue killed — all pending tasks cleared.")
    }

    fun reset() {
        isKilled.set(false)
        queue.clear()
    }

    fun size(): Int = queue.size
}
