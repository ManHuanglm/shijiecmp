package com.shiping.desktop.data.prefs

import com.shiping.shared.prefs.KeyValueStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * 桌面版应用偏好（Properties 文件持久化，数据目录下 prefs.properties）。
 * 键语义对齐 app PreferencesManager（当前源、默认解析、播放器设置等）。
 */
class DesktopPrefs(dataDir: File) : KeyValueStore {

    private val file = File(dataDir, "prefs.properties")
    private val props = Properties()

    init {
        runCatching {
            if (file.exists()) FileInputStream(file).use { props.loadFromXML(it) }
        }
    }

    override fun getString(key: String, def: String): String = props.getProperty(key, def)

    override fun getInt(key: String, def: Int): Int = props.getProperty(key)?.toIntOrNull() ?: def

    override fun getLong(key: String, def: Long): Long = props.getProperty(key)?.toLongOrNull() ?: def

    override fun getFloat(key: String, def: Float): Float = props.getProperty(key)?.toFloatOrNull() ?: def

    override fun getBoolean(key: String, def: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: def

    override fun put(key: String, value: Any?) {
        if (value == null) props.remove(key) else props.setProperty(key, value.toString())
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { props.storeToXML(it, "shiping-desktop preferences") }
        }
    }

    companion object {

        // 键常量统一收口于 com.shiping.shared.prefs.PrefsKeys（jvmCommon）：
        // 这里保留同名引用以兼容既有 UI 代码（cmpMain 统一后改用 PrefsKeys）
        val KEY_CURRENT_API_ID = com.shiping.shared.prefs.PrefsKeys.KEY_CURRENT_API_ID
        val KEY_CURRENT_TV_ID = com.shiping.shared.prefs.PrefsKeys.KEY_CURRENT_TV_ID
        val KEY_TV_FAVORITES = com.shiping.shared.prefs.PrefsKeys.KEY_TV_FAVORITES
        val KEY_DEFAULT_PARSER_URL = com.shiping.shared.prefs.PrefsKeys.KEY_DEFAULT_PARSER_URL
        val KEY_PLAYER_VOLUME = com.shiping.shared.prefs.PrefsKeys.KEY_PLAYER_VOLUME
        val KEY_PLAYER_SPEED = com.shiping.shared.prefs.PrefsKeys.KEY_PLAYER_SPEED
        val KEY_DATA_DIR = com.shiping.shared.prefs.PrefsKeys.KEY_DATA_DIR
        val KEY_FILTER_SLICE_AD = com.shiping.shared.prefs.PrefsKeys.KEY_FILTER_SLICE_AD
        val KEY_PLAY_RECORD_ENABLED = com.shiping.shared.prefs.PrefsKeys.KEY_PLAY_RECORD_ENABLED
        val KEY_TOUCH_HINT = com.shiping.shared.prefs.PrefsKeys.KEY_TOUCH_HINT
        val KEY_HTTP_PROXY = com.shiping.shared.prefs.PrefsKeys.KEY_HTTP_PROXY
        val KEY_MPV_HWDEC = com.shiping.shared.prefs.PrefsKeys.KEY_MPV_HWDEC
        val KEY_MPV_FX_SUBTITLE = com.shiping.shared.prefs.PrefsKeys.KEY_MPV_FX_SUBTITLE
        val KEY_SUB_COLOR = com.shiping.shared.prefs.PrefsKeys.KEY_SUB_COLOR
        val KEY_SUB_BACK_COLOR = com.shiping.shared.prefs.PrefsKeys.KEY_SUB_BACK_COLOR
        val KEY_SUB_BACK_ALPHA = com.shiping.shared.prefs.PrefsKeys.KEY_SUB_BACK_ALPHA
        val KEY_SUB_SIZE = com.shiping.shared.prefs.PrefsKeys.KEY_SUB_SIZE
        val KEY_VIDEO_SCALE = com.shiping.shared.prefs.PrefsKeys.KEY_VIDEO_SCALE
        val KEY_SHOW_PREV_NEXT = com.shiping.shared.prefs.PrefsKeys.KEY_SHOW_PREV_NEXT
        val KEY_PAUSE_ON_BACKGROUND = com.shiping.shared.prefs.PrefsKeys.KEY_PAUSE_ON_BACKGROUND
        val KEY_SHOW_TOTAL_TIME = com.shiping.shared.prefs.PrefsKeys.KEY_SHOW_TOTAL_TIME
        val KEY_SHOW_SEEK_CANCEL = com.shiping.shared.prefs.PrefsKeys.KEY_SHOW_SEEK_CANCEL
        val KEY_GLOBAL_SPEED = com.shiping.shared.prefs.PrefsKeys.KEY_GLOBAL_SPEED
        val KEY_SEEK_STEP = com.shiping.shared.prefs.PrefsKeys.KEY_SEEK_STEP
        val KEY_LONG_PRESS_SPEED = com.shiping.shared.prefs.PrefsKeys.KEY_LONG_PRESS_SPEED
        val KEY_M3U8_CHUNK_KB = com.shiping.shared.prefs.PrefsKeys.KEY_M3U8_CHUNK_KB
        val KEY_M3U8_THREADS = com.shiping.shared.prefs.PrefsKeys.KEY_M3U8_THREADS
        val KEY_M3U8_RISK_ACK = com.shiping.shared.prefs.PrefsKeys.KEY_M3U8_RISK_ACK
        val KEY_THEME_MODE = com.shiping.shared.prefs.PrefsKeys.KEY_THEME_MODE
        val KEY_THEME_COLOR = com.shiping.shared.prefs.PrefsKeys.KEY_THEME_COLOR
        val KEY_LANGUAGE = com.shiping.shared.prefs.PrefsKeys.KEY_LANGUAGE
        val KEY_HIDE_TABS = com.shiping.shared.prefs.PrefsKeys.KEY_HIDE_TABS
        val KEY_FONT_SCALE = com.shiping.shared.prefs.PrefsKeys.KEY_FONT_SCALE
        val KEY_FONT_NAME = com.shiping.shared.prefs.PrefsKeys.KEY_FONT_NAME

        /** 下载产物存储目录（空 = <dataDir>/downloads），自定义存储路径页配置，仅桌面使用 */
        const val KEY_DOWNLOAD_DIR = "downloadDir"

        /** 本地包根目录（clan://localhost/ 相对形态与 file:// 相对形态的解析基准），仅桌面使用 */
        const val KEY_LOCAL_ROOT = "localRoot"
    }
}
