package com.shiping.desktop.player

import com.shiping.shared.prefs.KeyValueStore

/**
 * 片头记录：按 影片+源+集 记录片头结束点（「记录片头」按钮），起播时自动跳过。
 * 键值存 KeyValueStore（jvmCommon：cmpMain 起播编排与桌面控制层共用），键用 hashCode 规避特殊字符与超长键名。
 */
object IntroMark {

    /** 片头记录目标（一部影片的某源某集） */
    data class Mark(val vodId: Int, val sourceUrl: String, val episodeName: String)

    private fun key(mark: Mark): String =
        "introEnd.${mark.vodId}.${mark.sourceUrl.hashCode()}.${mark.episodeName.hashCode()}"

    /** 记录片头结束点（毫秒） */
    fun save(prefs: KeyValueStore, mark: Mark, endMs: Long) {
        prefs.put(key(mark), endMs)
    }

    /** 读取片头结束点（未记录返回 0） */
    fun endMs(prefs: KeyValueStore, mark: Mark): Long = prefs.getLong(key(mark), 0L)
}
