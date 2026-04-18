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
    private const val PREF_LEVEL_KEY = "fileLogLevel"
    private const val LOG_SUB_DIR = "LOG"
    private const val LOG_PREFIX = "HYBRIDTALK_"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024
    private const val MAX_FILE_COUNT = 10
    /** payload 한 줄 최대 길이. raw push/sync JSON 이 거대해 큐 포화/디스크 폭증을 막는 안전망. */
    private const val MAX_LINE_CHARS = 1500

    enum class LogLevel(val priority: Int) {
        DEBUG(0), INFO(1), WARN(2), ERROR(3);
        companion object { fun parse(s: String?) = values().firstOrNull { it.name == s } ?: INFO }
    }

    private var enabled: Boolean = false
    @Volatile private var minLevel: LogLevel = LogLevel.INFO
    private var fileHandler: FileHandler? = null
    private val queue = LinkedBlockingQueue<String>(5000)

    @Volatile
    private var writerThread: Thread? = null

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        enabled = sp.getString(PREF_KEY, "N") == "Y"
        minLevel = LogLevel.parse(sp.getString(PREF_LEVEL_KEY, LogLevel.INFO.name))
        if (enabled) openFileHandler(context)
    }

    fun isEnabled() = enabled

    fun setMinLevel(context: Context, level: LogLevel) {
        minLevel = level
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_LEVEL_KEY, level.name).apply()
    }

    fun getMinLevel(): LogLevel = minLevel

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

    /**
     * 기본 INFO 레벨로 기록. 호출부 변경 없이 backward-compatible.
     * payload 가 [MAX_LINE_CHARS] 초과면 잘라서 기록.
     */
    fun log(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)

    fun log(level: LogLevel, tag: String, msg: String) {
        if (!enabled) return
        if (level.priority < minLevel.priority) return
        val truncated = if (msg.length > MAX_LINE_CHARS) msg.substring(0, MAX_LINE_CHARS) + "...(truncated ${msg.length - MAX_LINE_CHARS}c)" else msg
        if (!queue.offer("${level.name[0]} $tag\t$truncated")) {
            Log.w("FileLogger", "log queue full, dropping: $tag")
        }
    }

    /**
     * 내부 저장소 (앱 전용 영역). 외부 저장소가 아니므로 다른 앱에서 접근 불가.
     * 공유는 ConfigLogFileActivity 가 FileProvider 로 명시적으로 허용한 경우에만 가능.
     * (이전 외부 저장소 경로의 잔존 로그는 한 번 사용자가 직접 정리 필요.)
     */
    fun getLogDir(context: Context): File =
        File(context.filesDir, LOG_SUB_DIR)

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
