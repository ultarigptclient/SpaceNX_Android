package net.spacenx.messenger.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord

object FileLogger {
    private const val PREF_NAME = "talkConfig"
    private const val PREF_KEY = "fileLog"
    private const val LOG_SUB_DIR = "LOG"
    private const val LOG_PREFIX = "HYBRIDTALK_"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024
    private const val MAX_FILE_COUNT = 10

    private var enabled: Boolean = false
    private var fileHandler: FileHandler? = null
    private val queue = LinkedBlockingQueue<String>(5000)

    @Volatile
    private var writerThread: Thread? = null

    fun init(context: Context) {
        enabled = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY, "N") == "Y"
        if (enabled) openFileHandler(context)
    }

    fun isEnabled() = enabled

    fun enable(context: Context) {
        deleteLogFiles(context)
        openFileHandler(context)
        enabled = true
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_KEY, "Y").apply()
        ensureWriterRunning()
    }

    fun disable(context: Context) {
        enabled = false
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_KEY, "N").apply()
        closeFileHandler()
        deleteLogFiles(context)
    }

    fun log(tag: String, msg: String) {
        if (!enabled) return
        if (!queue.offer("$tag\t$msg")) {
            Log.w("FileLogger", "log queue full, dropping: $tag")
        }
    }

    fun getLogDir(context: Context): File =
        File(context.getExternalFilesDir(null), LOG_SUB_DIR)

    fun getLogFiles(context: Context): List<File> =
        getLogDir(context).listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    fun getTotalLogSize(context: Context): Long =
        getLogFiles(context).sumOf { it.length() }

    fun flush() {
        fileHandler?.flush()
    }

    @Synchronized
    private fun openFileHandler(context: Context) {
        try {
            val dir = getLogDir(context)
            if (!dir.exists()) dir.mkdirs()
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val pattern = "${dir.absolutePath}/${LOG_PREFIX}${today}_%g.log"
            fileHandler = FileHandler(pattern, MAX_FILE_SIZE, MAX_FILE_COUNT, true).apply {
                formatter = object : Formatter() {
                    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS ", Locale.getDefault())
                    override fun format(r: LogRecord) = "${fmt.format(Date(r.millis))}${r.message}\n"
                }
            }
            ensureWriterRunning()
        } catch (e: Exception) {
            Log.e("FileLogger", "openFileHandler failed", e)
        }
    }

    @Synchronized
    private fun closeFileHandler() {
        fileHandler?.close()
        fileHandler = null
    }

    private fun deleteLogFiles(context: Context) {
        closeFileHandler()
        getLogDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun ensureWriterRunning() {
        if (writerThread?.isAlive == true) return
        writerThread = Thread({
            while (enabled || queue.isNotEmpty()) {
                try {
                    val line = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    fileHandler?.publish(LogRecord(Level.ALL, line))
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("FileLogger", "write error", e)
                }
            }
        }, "FileLogger-writer").also { it.isDaemon = true; it.start() }
    }
}
