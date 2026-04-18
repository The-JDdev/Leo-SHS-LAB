package com.shslab.leo.memory

import android.content.Context
import com.shslab.leo.core.Logger
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ══════════════════════════════════════════
 *  LEO MEMORY MANAGER — SHS LAB
 *
 *  Lightweight RAG using TF-IDF + cosine similarity.
 *  No native embeddings needed → 2GB RAM safe.
 *  Max ~800 active memories kept hot.
 * ══════════════════════════════════════════
 */
object MemoryManager {

    private lateinit var db: LeoMemoryDb
    private val stopWords = setOf(
        "the","a","an","is","are","was","were","i","you","me","my","your","to","of",
        "and","or","but","if","in","on","at","for","with","this","that","it","be","do",
        "did","done","have","has","had","will","would","could","should","can","please"
    )

    fun init(ctx: Context) {
        db = LeoMemoryDb(ctx.applicationContext)
        // Warm up + decay
        Thread {
            try {
                val n = db.count()
                Logger.system("[Memory] $n memories online")
                db.decayLowImportance(olderThanMs = 14L * 24 * 3600 * 1000)
            } catch (t: Throwable) { Logger.warn("[Memory] init: ${t.message}") }
        }.start()
    }

    fun store(kind: String, content: String, importance: Int = 5): Long {
        if (!::db.isInitialized) return -1
        val tokens = tokenize(content).joinToString(" ")
        return try {
            db.insertMemory(kind, content.take(2000), tokens, importance)
        } catch (t: Throwable) { Logger.warn("[Memory] store: ${t.message}"); -1 }
    }

    fun storeChat(userMsg: String, assistantMsg: String) {
        store("episodic", "JD: $userMsg\nLeo: $assistantMsg", importance = 5)
    }

    fun storeFact(fact: String) = store("semantic", fact, importance = 9)

    /**
     * Recall the top-N most relevant memories for the given query.
     * Uses TF-IDF cosine similarity over tokenized content.
     */
    fun recall(query: String, topN: Int = 5): List<String> {
        if (!::db.isInitialized) return emptyList()
        val rows = try { db.fetchAllForRecall(800) } catch (t: Throwable) { return emptyList() }
        if (rows.isEmpty()) return emptyList()

        val qTokens = tokenize(query)
        if (qTokens.isEmpty()) return emptyList()

        // Document frequency
        val df = HashMap<String, Int>()
        rows.forEach { row ->
            row.tokens.split(' ').toHashSet().forEach { tok ->
                if (tok.isNotEmpty()) df[tok] = (df[tok] ?: 0) + 1
            }
        }
        val n = rows.size.toDouble()
        fun idf(t: String) = ln((n + 1.0) / ((df[t] ?: 0) + 1.0)) + 1.0

        val qVec = HashMap<String, Double>()
        qTokens.forEach { t -> qVec[t] = (qVec[t] ?: 0.0) + 1.0 }
        qVec.forEach { (t, tf) -> qVec[t] = tf * idf(t) }
        val qNorm = sqrt(qVec.values.sumOf { it * it })

        val scored = rows.map { row ->
            val docTokens = row.tokens.split(' ')
            val dVec = HashMap<String, Double>()
            docTokens.forEach { if (it.isNotEmpty()) dVec[it] = (dVec[it] ?: 0.0) + 1.0 }
            dVec.forEach { (t, tf) -> dVec[t] = tf * idf(t) }
            val dNorm = sqrt(dVec.values.sumOf { it * it })
            var dot = 0.0
            qVec.forEach { (t, qw) -> dVec[t]?.let { dot += qw * it } }
            val sim = if (qNorm == 0.0 || dNorm == 0.0) 0.0 else dot / (qNorm * dNorm)
            // Boost: importance + recency
            val ageDays = (System.currentTimeMillis() - row.timestamp) / 86_400_000.0
            val recencyBoost = 1.0 / (1.0 + ageDays / 7.0)
            val finalScore = sim * (1.0 + row.importance / 10.0) * (0.6 + 0.4 * recencyBoost)
            row to finalScore
        }.sortedByDescending { it.second }
         .take(topN)
         .filter { it.second > 0.05 }

        scored.forEach { (row, _) -> try { db.touchMemory(row.id) } catch (_: Throwable) {} }
        return scored.map { it.first.content }
    }

    fun buildRagContext(userQuery: String): String {
        val hits = recall(userQuery, topN = 5)
        if (hits.isEmpty()) return ""
        return buildString {
            appendLine("═══ RELEVANT MEMORIES (RAG) ═══")
            hits.forEachIndexed { i, m -> appendLine("[MEMORY #${i+1}] $m") }
            appendLine("═══════════════════════════════")
        }
    }

    fun stats(): String = if (::db.isInitialized) "${db.count()} memories" else "memory offline"

    fun wipeAll() { if (::db.isInitialized) try { db.wipe() } catch (_: Throwable) {} }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length in 2..30 && it !in stopWords }
    }
}
