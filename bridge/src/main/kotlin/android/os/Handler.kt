package android.os

/**
 * 桌面 stub：android.os.Handler（桌面无消息队列，post/sendMessage 立即在当前
 * 线程同步执行，带延迟的交给守护线程；jar 内继承 Handler 重写 handleMessage 仍可工作）。
 */
open class Handler {

    constructor()

    constructor(callback: Callback?)

    constructor(looper: Looper?)

    constructor(looper: Looper?, callback: Callback?)

    fun interface Callback {

        fun handleMessage(msg: Message): Boolean
    }

    open fun handleMessage(msg: Message) = Unit

    fun dispatchMessage(msg: Message) {
        handleMessage(msg)
    }

    fun post(runnable: Runnable): Boolean = executeDelayed(runnable, 0L)

    fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean = executeDelayed(runnable, delayMillis)

    fun postAtTime(runnable: Runnable, uptimeMillis: Long): Boolean = executeDelayed(runnable, 0L)

    fun removeCallbacks(runnable: Runnable) = Unit

    fun removeCallbacksAndMessages(token: Any?) = Unit

    fun sendMessage(msg: Message): Boolean = executeDelayed({ dispatchMessage(msg) }, 0L)

    fun sendMessageDelayed(msg: Message, delayMillis: Long): Boolean = executeDelayed({ dispatchMessage(msg) }, delayMillis)

    fun sendEmptyMessage(what: Int): Boolean = executeDelayed({ dispatchMessage(Message().apply { this.what = what }) }, 0L)

    fun hasMessages(what: Int): Boolean = false

    fun obtainMessage(): Message = Message().apply { target = this@Handler }

    fun obtainMessage(what: Int): Message = Message().apply { this.what = what; target = this@Handler }

    /** 原型不引入消息队列线程：零延迟直接执行，正延迟交给守护线程 */
    private fun executeDelayed(runnable: Runnable, delayMillis: Long): Boolean {
        if (delayMillis <= 0L) {
            runnable.run()
            return true
        }
        Thread {
            try {
                Thread.sleep(delayMillis)
            } catch (_: InterruptedException) {
                return@Thread
            }
            runnable.run()
        }.apply { isDaemon = true }.start()
        return true
    }
}
