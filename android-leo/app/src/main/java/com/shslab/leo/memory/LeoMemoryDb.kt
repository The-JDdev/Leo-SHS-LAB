package com.shslab.leo.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * ══════════════════════════════════════════
 *  LEO RAG MEMORY DATABASE — SHS LAB
 *
 *  Three-tier persistent memory:
 *   - episodic: every conversation turn (decays)
 *   - semantic: facts about JD ("My GitHub is X")
 *   - skill:    learned procedures
 * ══════════════════════════════════════════
 */
class LeoMemoryDb(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "leo_memory.db"
        const val DB_VERSION = 1

        const val TABLE = "memories"
        const val COL_ID         = "id"
        const val COL_KIND       = "kind"        // episodic / semantic / skill
        const val COL_CONTENT    = "content"
        const val COL_TOKENS     = "tokens"      // space-separated normalized tokens for TF
        const val COL_TIMESTAMP  = "ts"
        const val COL_IMPORTANCE = "importance"  // 0..10
        const val COL_USAGE      = "usage_count"
        const val COL_LAST_USED  = "last_used"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_KIND TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_TOKENS TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_IMPORTANCE INTEGER NOT NULL DEFAULT 5,
                $COL_USAGE INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_USED INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_kind ON $TABLE($COL_KIND)")
        db.execSQL("CREATE INDEX idx_ts ON $TABLE($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insertMemory(kind: String, content: String, tokens: String, importance: Int): Long {
        val v = ContentValues().apply {
            put(COL_KIND, kind)
            put(COL_CONTENT, content)
            put(COL_TOKENS, tokens)
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_IMPORTANCE, importance.coerceIn(0, 10))
        }
        return writableDatabase.insert(TABLE, null, v)
    }

    fun fetchAllForRecall(maxRows: Int = 800): List<MemoryRow> {
        val out = mutableListOf<MemoryRow>()
        val c = readableDatabase.query(
            TABLE, null, null, null, null, null,
            "$COL_IMPORTANCE DESC, $COL_TIMESTAMP DESC", maxRows.toString()
        )
        c.use {
            while (it.moveToNext()) {
                out += MemoryRow(
                    id         = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    kind       = it.getString(it.getColumnIndexOrThrow(COL_KIND)),
                    content    = it.getString(it.getColumnIndexOrThrow(COL_CONTENT)),
                    tokens     = it.getString(it.getColumnIndexOrThrow(COL_TOKENS)),
                    timestamp  = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    importance = it.getInt(it.getColumnIndexOrThrow(COL_IMPORTANCE)),
                    usage      = it.getInt(it.getColumnIndexOrThrow(COL_USAGE))
                )
            }
        }
        return out
    }

    fun touchMemory(id: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET $COL_USAGE = $COL_USAGE + 1, $COL_LAST_USED = ? WHERE $COL_ID = ?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    fun decayLowImportance(olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE $COL_KIND='episodic' AND $COL_IMPORTANCE<=3 AND $COL_LAST_USED<? AND $COL_TIMESTAMP<?",
            arrayOf(cutoff, cutoff)
        )
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun wipe() {
        writableDatabase.execSQL("DELETE FROM $TABLE")
    }
}

data class MemoryRow(
    val id: Long,
    val kind: String,
    val content: String,
    val tokens: String,
    val timestamp: Long,
    val importance: Int,
    val usage: Int
)
