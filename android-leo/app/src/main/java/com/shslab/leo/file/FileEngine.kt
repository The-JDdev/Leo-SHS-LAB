package com.shslab.leo.file

import com.shslab.leo.cognitive.CognitiveCleaner
import com.shslab.leo.core.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * ══════════════════════════════════════════
 *  LEO FILE ENGINE — SHS LAB
 *
 *  Direct java.io.File access to /sdcard/
 *  Target API 29 with requestLegacyExternalStorage.
 *  All operations run on caller's thread.
 *  Pair with background IO dispatcher.
 * ══════════════════════════════════════════
 */
class FileEngine {

    companion object {
        private const val LEO_ROOT = "/sdcard/Leo/"
    }

    init {
        // Ensure Leo workspace root exists
        File(LEO_ROOT).mkdirs()
    }

    /**
     * Create a directory at the given path.
     * Resolves relative paths under /sdcard/Leo/
     */
    fun createDirectory(path: String): Boolean {
        val dir = resolvePath(path)
        val created = dir.mkdirs()
        Logger.file("[FS] mkdir ${dir.absolutePath} → ${if (created) "OK" else "already exists"}")
        return created || dir.exists()
    }

    /**
     * Write content to a file (creates parent dirs automatically).
     * Overwrites existing content.
     */
    fun writeCodeFile(path: String, content: String): Boolean {
        val file = resolvePath(path)
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(file, false).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            Logger.file("[FS] Written: ${file.absolutePath} (${content.length} bytes)")
            true
        } catch (e: Exception) {
            Logger.error("[FS] Write failed: ${e.message}")
            false
        }
    }

    /**
     * Append content to a file.
     */
    fun appendToFile(path: String, content: String): Boolean {
        val file = resolvePath(path)
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            Logger.error("[FS] Append failed: ${e.message}")
            false
        }
    }

    /**
     * Read file content as string.
     * @return file content or empty string on failure
     */
    fun readFileContent(path: String): String {
        val file = resolvePath(path)
        return try {
            if (!file.exists()) {
                Logger.warn("[FS] File not found: ${file.absolutePath}")
                return ""
            }
            file.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Logger.error("[FS] Read failed: ${e.message}")
            ""
        }
    }

    /**
     * Delete a file or directory.
     * SAFETY: Routes through CognitiveCleaner for verification.
     */
    fun deleteTarget(path: String, reason: String = "") {
        val file = resolvePath(path)
        CognitiveCleaner.verifyAndClean(file.absolutePath, reason) {
            // Actual deletion callback — only called if cleaner approves
            val result = if (file.isDirectory) file.deleteRecursively() else file.delete()
            Logger.file("[FS] Deleted: ${file.absolutePath} → $result")
        }
    }

    /**
     * List files in a directory.
     */
    fun listDirectory(path: String): List<String> {
        val dir = resolvePath(path)
        return if (dir.isDirectory) {
            dir.listFiles()?.map { it.name } ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Check if a path exists.
     */
    fun exists(path: String): Boolean = resolvePath(path).exists()

    /**
     * Resolve path: absolute or relative to /sdcard/Leo/
     */
    private fun resolvePath(path: String): File {
        return if (path.startsWith("/")) File(path) else File(LEO_ROOT, path)
    }
}
