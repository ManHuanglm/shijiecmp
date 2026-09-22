package com.shiping.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 应用内日志工具，方便开发调试与用户反馈问题。
 *
 * - 内存环形缓冲（[MAX_MEMORY] 条），日志页实时查看
 * - 同步落盘到 filesDir/logs/app.log，按大小轮转（[MAX_FILE_BYTES]，保留 [MAX_FILES] 个）
 * - 自动注册全局未捕获异常处理器，闪退堆栈写入日志
 * - 同时输出到 logcat
 */
object AppLog {

    enum class Level(val value: Int, val label: String) {
        V(2, "V"), D(3, "D"), I(4, "I"), W(5, "W"), E(6, "E"),
    }

    data class Entry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwableText: String?,
    )

    private const val MAX_MEMORY = 3000
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_FILES = 4
    private const val LOG_DIR = "logs"
    private const val LOG_NAME = "app.log"

    private val lock = Any()
    private val memory = ArrayDeque<Entry>()

    private val _revision = MutableStateFlow(0L)
    /** 日志新增/清空时自增，UI 据此刷新 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-log").apply { isDaemon = true }
    }

    private lateinit var logDir: File
    private lateinit var logFile: File
    private var writer: java.io.BufferedWriter? = null
    @Volatile private var initialized = false

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 初始化日志目录并注册全局崩溃捕获，应在 Application.onCreate 调用 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        logDir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        logFile = File(logDir, LOG_NAME)
        installCrashHandler()
        i("AppLog", "日志系统初始化完成，进程启动 ${android.os.Process.myPid()}")
    }

    // region 对外记录方法

    fun v(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.V, tag, message, throwable)

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.D, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.I, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.W, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.E, tag, message, throwable)

    // endregion

    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        val tText = throwable?.let { stackToString(it) }
        val entry = Entry(System.currentTimeMillis(), level, tag, message, tText)

        synchronized(lock) {
            memory.addLast(entry)
            while (memory.size > MAX_MEMORY) memory.removeFirst()
            _revision.value = _revision.value + 1
        }

        // logcat
        val priority = when (level) {
            Level.V -> android.util.Log.VERBOSE
            Level.D -> android.util.Log.DEBUG
            Level.I -> android.util.Log.INFO
            Level.W -> android.util.Log.WARN
            Level.E -> android.util.Log.ERROR
        }
        if (throwable != null) android.util.Log.println(priority, tag, message + '\n' + tText)
        else android.util.Log.println(priority, tag, message)

        if (initialized) {
            io.execute { writeToFile(entry) }
        }
    }

    /** 获取过滤后的日志快照（时间正序） */
    fun snapshot(minLevel: Level = Level.V): List<Entry> = synchronized(lock) {
        if (minLevel == Level.V) memory.toList()
        else memory.filter { it.level.value >= minLevel.value }
    }

    /** 清空内存与磁盘日志 */
    fun clear() {
        io.execute {
            synchronized(lock) {
                runCatching {
                    writer?.close()
                    writer = null
                    logFile.delete()
                    for (i in 1 until MAX_FILES) File(logDir, "$LOG_NAME.$i").delete()
                    memory.clear()
                    _revision.value = _revision.value + 1
                }
            }
        }
    }

    /** 用于系统分享的日志文件（含历史轮转内容合并） */
    fun exportFile(): File {
        val flushHappened = java.util.concurrent.CountDownLatch(1)
        io.execute { flushHappened.countDown() }
        flushHappened.await(2, TimeUnit.SECONDS)
        val merged = File(logDir, "app-log-export.txt")
        merged.bufferedWriter().use { out ->
            for (i in (MAX_FILES - 1) downTo 1) {
                val f = File(logDir, "$LOG_NAME.$i")
                if (f.exists()) f.bufferedReader().use { it.copyTo(out) }
            }
            if (logFile.exists()) logFile.bufferedReader().use { it.copyTo(out) }
        }
        return merged
    }

    // region 内部实现

    private fun writeToFile(entry: Entry) {
        try {
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                rotate()
            }
            val w = writer ?: java.io.BufferedWriter(java.io.FileWriter(logFile, true)).also {
                writer = it
            }
            w.write(formatLine(entry))
            w.write("\n")
            w.flush()
        } catch (_: Throwable) {
            // 日志失败不影响主流程
        }
    }

    private fun rotate() {
        runCatching {
            writer?.close()
            writer = null
            for (i in (MAX_FILES - 1) downTo 1) {
                val from = File(logDir, if (i == 1) LOG_NAME else "$LOG_NAME.${i - 1}")
                val to = File(logDir, "$LOG_NAME.$i")
                if (to.exists()) to.delete()
                if (from.exists()) from.renameTo(to)
            }
        }
    }

    private fun formatLine(entry: Entry): String {
        val sb = StringBuilder(96)
        sb.append(timeFormat.format(Date(entry.timeMs)))
        sb.append(' ').append(entry.level.label).append('/')
        sb.append(entry.tag).append(": ").append(entry.message)
        if (entry.throwableText != null) {
            sb.append('\n').append(entry.throwableText)
        }
        return sb.toString()
    }

    private fun stackToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 崩溃日志同步落盘，避免进程被杀后异步队列丢失
            try {
                val entry = Entry(
                    System.currentTimeMillis(),
                    Level.E,
                    "CRASH",
                    "未捕获异常 thread=${thread.name}(id=${thread.id})",
                    stackToString(throwable),
                )
                synchronized(lock) {
                    memory.addLast(entry)
                    while (memory.size > MAX_MEMORY) memory.removeFirst()
                }
                writeToFile(entry)
            } catch (_: Throwable) {
                // 忽略日志自身异常
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    // endregion
}
