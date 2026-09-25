package com.castla.mirror.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent on-disk logger with size-bounded rotation. All persisted writes
 * are sanitized via [DiagnosticSanitizer] so URLs, intent extras, and shell
 * command bodies never hit disk.
 *
 * Storage: `<filesDir>/logs/mirror.log` (current) + `mirror.log.1` (rotated).
 * Cap ~512 KB per file by default → ~1 MB total on disk.
 *
 * If init fails (e.g. read-only filesystem), the logger silently degrades to
 * no-op mode rather than crashing the app.
 *
 * Durability: W/E lines (and I lines logged with `durable = true`) are fsync'ed
 * before returning. A phone that hard-reboots loses whatever was still only in
 * the page cache, so the breadcrumb written right before a risky call (panel
 * power, VD creation) must actually reach flash to be useful post-mortem.
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val DEFAULT_MAX_FILE_BYTES = 512_000L

    private val lock = Any()
    @Volatile private var initialized = false
    @Volatile private var degraded = false
    private var logsDir: File? = null
    private var maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES

    private val timestampFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    }

    /** Production init from [Context]. Idempotent; safe on init failure. */
    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            tryInit(File(context.filesDir, "logs"), DEFAULT_MAX_FILE_BYTES)
        }
    }

    /** Test-only init taking an explicit parent directory and cap. */
    internal fun initForTest(parent: File, maxFileBytes: Long) {
        synchronized(lock) {
            if (initialized) return
            tryInit(File(parent, "logs"), maxFileBytes)
        }
    }

    /** Test-only: clear initialization state so subsequent init() rebinds. */
    internal fun resetForTest() {
        synchronized(lock) {
            initialized = false
            degraded = false
            logsDir = null
            maxFileBytes = DEFAULT_MAX_FILE_BYTES
        }
    }

    private fun tryInit(dir: File, maxBytes: Long) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Could not create logs dir: $dir — degraded mode")
                degraded = true
                initialized = true
                return
            }
            if (!dir.isDirectory) {
                Log.w(TAG, "Path exists but is not a directory: $dir — degraded mode")
                degraded = true
                initialized = true
                return
            }
            this.logsDir = dir
            this.maxFileBytes = maxBytes
            this.degraded = false
            this.initialized = true
        } catch (t: Throwable) {
            Log.w(TAG, "FileLogger init failed — degraded mode", t)
            degraded = true
            initialized = true
        }
    }

    fun i(tag: String, msg: String, durable: Boolean = false) = write("I", tag, msg, null, durable)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t, durable = true)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t, durable = true)

    fun getLogFiles(): List<File> {
        synchronized(lock) {
            val dir = logsDir ?: return emptyList()
            val current = File(dir, "mirror.log")
            val rotated = File(dir, "mirror.log.1")
            return listOf(current, rotated).filter { it.exists() && it.length() > 0 }
        }
    }

    /**
     * The newest [maxChars] characters of the log, spanning the rotated file and
     * the current one in chronological order, starting on a full line. Reads at
     * most ~4 bytes per requested char from each file, so it stays cheap even
     * with full 512 KB files.
     */
    fun readRecentTail(maxChars: Int): String {
        if (maxChars <= 0) return ""
        val text = synchronized(lock) {
            val dir = logsDir ?: return ""
            val maxBytes = maxChars.toLong() * 4
            val current = File(dir, "mirror.log")
            val rotated = File(dir, "mirror.log.1")
            val currentBytes = readTailBytes(current, maxBytes)
            val remaining = maxBytes - currentBytes.size
            val rotatedBytes = if (remaining > 0) readTailBytes(rotated, remaining) else ByteArray(0)
            // Decode with replacement so a cut through a multi-byte codepoint can't throw.
            String(rotatedBytes, Charsets.UTF_8) + String(currentBytes, Charsets.UTF_8)
        }
        return DiagnosticReport.tailAtLineBoundary(text, maxChars)
    }

    private fun readTailBytes(file: File, maxBytes: Long): ByteArray {
        if (!file.exists() || maxBytes <= 0) return ByteArray(0)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = (len - maxBytes).coerceAtLeast(0)
                val out = ByteArray((len - start).toInt())
                raf.seek(start)
                raf.readFully(out)
                out
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read log tail from $file", t)
            ByteArray(0)
        }
    }

    fun clear() {
        synchronized(lock) {
            val dir = logsDir ?: return
            File(dir, "mirror.log").delete()
            File(dir, "mirror.log.1").delete()
        }
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?, durable: Boolean) {
        if (!initialized || degraded) return
        val safe = DiagnosticSanitizer.safeMessage(msg)
        val ts = timestampFmt.get()?.format(Date()) ?: ""
        val tname = Thread.currentThread().name
        val entry = buildString {
            append("$ts $level $tag: $safe (t=$tname)").append('\n')
            if (t != null) {
                val sw = StringWriter()
                PrintWriter(sw).use { t.printStackTrace(it) }
                append(sw.toString())
            }
        }
        synchronized(lock) {
            val dir = logsDir ?: return
            try {
                val current = File(dir, "mirror.log")
                if (current.exists() && current.length() >= maxFileBytes) {
                    val rotated = File(dir, "mirror.log.1")
                    if (rotated.exists()) rotated.delete()
                    current.renameTo(rotated)
                }
                FileOutputStream(current, true).use { out ->
                    out.write(entry.toByteArray(Charsets.UTF_8))
                    if (durable) {
                        try { out.fd.sync() } catch (_: Throwable) { }
                    }
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Failed to write log entry", failure)
            }
        }
    }
}
