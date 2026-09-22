package android.preference

import android.content.Context

/** 桌面 stub：偏好管理器（复用 SharedPreferences 桩的磁盘持久化实现） */
object PreferenceManager {

    @JvmStatic
    fun getDefaultSharedPreferences(context: Context?): android.content.SharedPreferences =
        context?.getSharedPreferences("default", Context.MODE_PRIVATE)
            ?: android.content.MemSharedPreferences.of("default")
}
