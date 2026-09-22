package com.shiping.shared.prefs

/**
 * 键值偏好统一接口（commonMain）：
 * Android 侧由 DataStore 封装实现（M4），桌面侧为 DesktopPrefs（Properties 文件）。
 * 键语义两端对齐（当前源、默认解析、播放器设置等）。
 */
interface KeyValueStore {

    fun getString(key: String, def: String = ""): String

    fun getInt(key: String, def: Int = 0): Int

    fun getLong(key: String, def: Long = 0L): Long

    fun getFloat(key: String, def: Float = 0f): Float

    fun getBoolean(key: String, def: Boolean = false): Boolean

    /** 写入偏好（value 为 null 表示移除该键） */
    fun put(key: String, value: Any?)
}
