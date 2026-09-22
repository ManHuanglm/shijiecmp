package android.os

/** 桌面 stub：Binder 标记接口（仅类加载校验） */
interface IBinder

/** 桌面 stub：进程与系统时钟（极少被爬虫真正调用，给无害实现） */
object Process {

    @JvmStatic
    fun myPid(): Int = 0

    @JvmStatic
    fun myUid(): Int = 1000

    @JvmStatic
    fun killProcess(pid: Int) = Unit

    @JvmStatic
    fun setThreadPriority(priority: Int) = Unit
}

/** 桌面 stub：系统时钟 */
object SystemClock {

    @JvmStatic
    fun sleep(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }

    @JvmStatic
    fun elapsedRealtime(): Long = System.currentTimeMillis()

    @JvmStatic
    fun uptimeMillis(): Long = System.currentTimeMillis()

    @JvmStatic
    fun currentThreadTimeMillis(): Long = System.currentTimeMillis()
}
