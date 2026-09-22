package com.shiping.desktop.ui

/**
 * cmpMain 统一日志出口：各平台启动时装配（桌面接 AppLog，Android 接 Log），
 * 统一 UI 层不感知平台日志实现。
 */
object VmLog {

    @Volatile
    var sink: (level: Char, tag: String, message: String) -> Unit = { _, _, _ -> }

    fun i(tag: String, message: String) = sink('i', tag, message)
    fun w(tag: String, message: String) = sink('w', tag, message)
    fun e(tag: String, message: String) = sink('e', tag, message)
}
