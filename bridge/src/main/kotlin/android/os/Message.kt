package android.os

/**
 * 桌面 stub：android.os.Message（what/arg/obj 字段容器，sendToTarget 立即派发）。
 */
class Message {

    var what: Int = 0
    var arg1: Int = 0
    var arg2: Int = 0
    var obj: Any? = null
    var target: Handler? = null

    fun sendToTarget(): Boolean {
        target?.dispatchMessage(this)
        return true
    }

    companion object {

        @JvmStatic
        fun obtain(): Message = Message()

        @JvmStatic
        fun obtain(what: Int): Message = Message().apply { this.what = what }
    }
}
