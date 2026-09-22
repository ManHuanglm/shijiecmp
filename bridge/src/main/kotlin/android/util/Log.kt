package android.util

/**
 * 桌面 stub：android.util.Log 输出到控制台（jar 内爬虫的日志入口）。
 */
object Log {

    @JvmStatic
    fun v(tag: String?, msg: String?): Int = log("V", tag, msg, null)

    @JvmStatic
    fun v(tag: String?, msg: String?, tr: Throwable?): Int = log("V", tag, msg, tr)

    @JvmStatic
    fun d(tag: String?, msg: String?): Int = log("D", tag, msg, null)

    @JvmStatic
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = log("D", tag, msg, tr)

    @JvmStatic
    fun i(tag: String?, msg: String?): Int = log("I", tag, msg, null)

    @JvmStatic
    fun i(tag: String?, msg: String?, tr: Throwable?): Int = log("I", tag, msg, tr)

    @JvmStatic
    fun w(tag: String?, msg: String?): Int = log("W", tag, msg, null)

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = log("W", tag, msg, tr)

    @JvmStatic
    fun w(tag: String?, tr: Throwable?): Int = log("W", tag, null, tr)

    @JvmStatic
    fun e(tag: String?, msg: String?): Int = log("E", tag, msg, null)

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = log("E", tag, msg, tr)

    @JvmStatic
    fun wtf(tag: String?, msg: String?): Int = log("WTF", tag, msg, null)

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString().orEmpty()

    /** 输出一行日志：错误级别走 stderr，其余走 stdout */
    private fun log(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        val line = buildString {
            append(level).append('/').append(tag ?: "Log").append(": ")
            if (!msg.isNullOrEmpty()) append(msg)
            if (tr != null) append(' ').append(tr.toString())
        }
        if (level == "W" || level == "E" || level == "WTF") System.err.println(line)
        else java.lang.System.out.println(line)
        return 0
    }
}
