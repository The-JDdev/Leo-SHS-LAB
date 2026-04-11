package com.shslab.leo.cognitive

import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════
 *  LEO COGNITIVE CLEANUP PROTOCOL — SHS LAB
 *
 *  SAFETY GATE for all file deletion operations.
 *  Deletion is PROHIBITED unless:
 *    1. Git push returned exit code 0, OR
 *    2. Reason explicitly matches "Temporary Cache"
 *
 *  Any other condition → ABORT + LOG WARNING.
 * ══════════════════════════════════════════
 */
object CognitiveCleaner {

    private const val ALLOWED_REASON_CACHE = "Temporary Cache"

    /**
     * Tracks the last git push exit code.
     * Must be updated by GitManager after every push.
     */
    @Volatile
    var lastGitPushExitCode: Int = -1

    /**
     * Verify deletion conditions before executing.
     *
     * @param targetPath  Absolute path to be deleted
     * @param deletionReason  Reason provided by the AI command ("value" field)
     * @param onApproved  Lambda executed ONLY if verification passes
     */
    fun verifyAndClean(
        targetPath: String,
        deletionReason: String,
        onApproved: () -> Unit
    ) {
        Logger.warn("[CognitiveCleaner] Deletion request received.")
        Logger.warn("[CognitiveCleaner] Target: $targetPath")
        Logger.warn("[CognitiveCleaner] Reason: '$deletionReason'")
        Logger.warn("[CognitiveCleaner] Last git push exit code: $lastGitPushExitCode")

        // ── CONDITION 1: Explicit allowed reason ──
        if (deletionReason.equals(ALLOWED_REASON_CACHE, ignoreCase = true)) {
            Logger.system("[CognitiveCleaner] APPROVED — Reason: Temporary Cache")
            executeSafeDeletion(targetPath, onApproved)
            return
        }

        // ── CONDITION 2: Git push was successful ──
        if (lastGitPushExitCode == 0) {
            Logger.git("[CognitiveCleaner] APPROVED — Git push verified (exit code 0)")
            executeSafeDeletion(targetPath, onApproved)
            return
        }

        // ── CONDITION 3: Invalid or missing reason + no successful push ──
        if (deletionReason.isBlank()) {
            Logger.error("[CognitiveCleaner] ABORTED — No deletion reason provided. Refusing deletion of: $targetPath")
            return
        }

        // ── DEFAULT: ABORT ──
        Logger.error(
            "[CognitiveCleaner] ABORTED — Reason '$deletionReason' is not a valid authorization. " +
                    "Git push exit code: $lastGitPushExitCode. File preserved: $targetPath"
        )
    }

    private fun executeSafeDeletion(targetPath: String, onApproved: () -> Unit) {
        try {
            onApproved()
            Logger.system("[CognitiveCleaner] Deletion executed and verified: $targetPath")
        } catch (e: Exception) {
            Logger.error("[CognitiveCleaner] Deletion callback threw exception: ${e.message}")
        }
    }

    /**
     * Called by GitManager after every push operation.
     */
    fun updateGitPushResult(exitCode: Int) {
        lastGitPushExitCode = exitCode
        if (exitCode == 0) {
            Logger.git("[CognitiveCleaner] Git push success recorded — deletion gate UNLOCKED")
        } else {
            Logger.warn("[CognitiveCleaner] Git push failed (code $exitCode) — deletion gate LOCKED")
        }
    }
}
