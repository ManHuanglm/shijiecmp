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

        /** 当前选中的 API 源 ID */
        const val KEY_CURRENT_API_ID = "currentApiId"

        /** 当前选中的直播源 ID */
        const val KEY_CURRENT_TV_ID = "currentTvId"

        /** 直播收藏频道（favoriteKey 逗号分隔） */
        const val KEY_TV_FAVORITES = "tvFavorites"

        /** 默认解析源地址 */
        const val KEY_DEFAULT_PARSER_URL = "defaultParserUrl"

        /** 播放音量（0-100） */
        const val KEY_PLAYER_VOLUME = "playerVolume"

        /** 播放倍速 */
        const val KEY_PLAYER_SPEED = "playerSpeed"

        // region 设置页（对齐 app 设置结构）

        /** 自定义数据目录（持久化于默认目录的 prefs，重启生效） */
        const val KEY_DATA_DIR = "dataDir"

        /** 过滤列表中名称/备注含「切片」的条目 */
        const val KEY_FILTER_SLICE_AD = "filterSliceAd"

        /** 播放记录开关（关闭后不保存播放进度） */
        const val KEY_PLAY_RECORD_ENABLED = "playRecordEnabled"

        /** 触感提示（桌面端无振动，仅保存设置） */
        const val KEY_TOUCH_HINT = "touchHint"

        /** HTTP 代理（host:port，对爬虫网络请求生效，重启生效） */
        const val KEY_HTTP_PROXY = "httpProxy"

        /** mpv 硬解码 */
        const val KEY_MPV_HWDEC = "mpvHwdec"

        /** mpv 特效字幕（保留 ASS 特效） */
        const val KEY_MPV_FX_SUBTITLE = "mpvFxSubtitle"

        /** 字幕颜色（#RRGGBB） */
        const val KEY_SUB_COLOR = "subColor"

        /** 字幕背景颜色（#RRGGBB） */
        const val KEY_SUB_BACK_COLOR = "subBackColor"

        /** 字幕背景色透明度（0-100） */
        const val KEY_SUB_BACK_ALPHA = "subBackAlpha"

        /** 字幕大小（百分比，50-200） */
        const val KEY_SUB_SIZE = "subSize"

        /** mpv 画面比例：fit=原比例 crop=裁剪铺满 stretch=拉伸 */
        const val KEY_VIDEO_SCALE = "videoScale"

        /** 显示上一集/下一集按钮 */
        const val KEY_SHOW_PREV_NEXT = "showPrevNext"

        /** 播放窗口最小化时暂停播放 */
        const val KEY_PAUSE_ON_BACKGROUND = "pauseOnBackground"

        /** 控制条显示总时长 */
        const val KEY_SHOW_TOTAL_TIME = "showTotalTime"

        /** 松手取消调整进度（移动端手势，仅保存设置） */
        const val KEY_SHOW_SEEK_CANCEL = "showSeekCancel"

        /** 全局倍速（浮点，起播默认速率） */
        const val KEY_GLOBAL_SPEED = "globalSpeed"

        /** 快进快退步长秒（0 不展示按钮） */
        const val KEY_SEEK_STEP = "seekStep"

        /** 长按倍速（移动端手势，仅保存设置） */
        const val KEY_LONG_PRESS_SPEED = "longPressSpeed"

        /** 多线程播放：分片大小 kb */
        const val KEY_M3U8_CHUNK_KB = "m3u8ChunkKb"

        /** 多线程播放：线程数 */
        const val KEY_M3U8_THREADS = "m3u8Threads"

        /** 多线程播放：已知晓风险并确认使用 */
        const val KEY_M3U8_RISK_ACK = "m3u8RiskAck"

        /** 主题模式：0=跟随系统 1=浅色 2=深色 */
        const val KEY_THEME_MODE = "theme_mode"

        /** 主题色名称（蓝/黄/青/红/绿/紫/橙） */
        const val KEY_THEME_COLOR = "theme_color"

        /** 界面语言（暂仅简体中文） */
        const val KEY_LANGUAGE = "language"

        /** 隐藏的侧边栏 Tab（逗号分隔名称，首页/设置不可隐藏） */
        const val KEY_HIDE_TABS = "hideTabs"

        /** 字体缩放比例（百分比，80-140） */
        const val KEY_FONT_SCALE = "fontScale"

        /** 字体（暂仅系统默认） */
        const val KEY_FONT_NAME = "fontName"

        // endregion
    }
}
