package com.shiping.desktop.util

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 单条日志（结构化，日志页按级别筛选/着色） */
data class LogEntry(
    val time: String,
    val level: Char,
    val tag: String,
    val message: String,
) {
    val text: String get() = "$time $level/$tag: $message"
}

/** 桌面版日志：stdout 输出 + 环形缓冲（设置页日志查看用）+ 可选文件落盘（打包版排障） */
object AppLog {

    private const val MAX_LINES = 500

    private val entries = ArrayDeque<LogEntry>()

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** 文件输出（AppContainer 初始化时注入；打包版 exe 无控制台，靠文件排障） */
    @Volatile
    private var logFile: File? = null

    /** 绑定日志文件（追加写入；失败静默降级为仅内存/stdout） */
    fun bindFile(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            logFile = file
        }
    }

    fun v(tag: String, message: String) = append('V', tag, message)

    fun d(tag: String, message: String) = append('D', tag, message)

    fun i(tag: String, message: String) = append('I', tag, message)

    fun w(tag: String, message: String) = append('W', tag, message)

    fun e(tag: String, message: String) = append('E', tag, message)

    private fun append(level: Char, tag: String, message: String) {
        val entry = LogEntry(LocalTime.now().format(timeFormat), level, tag, message)
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_LINES) entries.removeFirst()
        }
        println(entry.text)
        logFile?.let { file ->
            runCatching { file.appendText("${entry.text}\n") }
        }
    }

    /** 环形缓冲快照（结构化，日志页筛选展示用） */
    fun snapshotEntries(): List<LogEntry> = synchronized(entries) { entries.toList() }

    /** 清空环形缓冲 */
    fun clear() = synchronized(entries) { entries.clear() }
}
