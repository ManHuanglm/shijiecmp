package android.os

/**
 * 桌面 stub：android.os.Looper（桌面无主线程消息队列，恒返回假想主线程 Looper）。
 */
class Looper private constructor() {

    fun isCurrentThread(): Boolean = true

    companion object {

        private val MAIN = Looper()

        @JvmStatic
        fun getMainLooper(): Looper = MAIN

        @JvmStatic
        fun myLooper(): Looper? = MAIN

        @JvmStatic
        fun getMainLooperThread(): Thread = Thread.currentThread()

        @JvmStatic
        fun prepare() = Unit

        @JvmStatic
        fun loop() = Unit
    }
}
