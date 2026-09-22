package com.shiping.desktop.player

import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.util.AppLog
import com.shiping.desktop.util.Constants
import com.shiping.shared.player.PlayerKernel as PlayerKernelContract
import java.awt.Component
import java.net.URLEncoder
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放内核抽象：mpv（JNA libmpv）与 VLC（vlcj libvlc）共用同一套状态语义，
 * 播放窗口（PlayerFrame）只面向此接口，不感知具体内核实现。
 * 挂接 shared commonMain 的 PlayerKernelContract（统一四端接口），renderSurface 桥接 swingComponent。
 * 注意：本文件内 enum PlayerKernel 为内核类型枚举（桌面历史命名），与 shared 接口以别名区分。
 */
interface PlayerController : PlayerKernelContract {

    /** 是否正在播放 */
    override val isPlaying: StateFlow<Boolean>

    /** 当前播放进度（毫秒） */
    override val timeMs: StateFlow<Long>

    /** 媒体总时长（毫秒） */
    override val durationMs: StateFlow<Long>

    /** 视频分辨率宽（像素，未知为 0） */
    override val videoWidth: StateFlow<Int>

    /** 视频分辨率高（像素，未知为 0） */
    override val videoHeight: StateFlow<Int>

    /** 网络缓冲中（画面卡顿加载） */
    override val isBuffering: StateFlow<Boolean>

    /** 播放期错误提示（非空时控制条展示） */
    override val errorText: StateFlow<String?>

    /** 内核初始化失败原因（缺 dll 等），非空时展示引导文案 */
    override val initError: StateFlow<String?>

    /** 视频渲染面（mpv/vlc 均为 wid 渲染的 Canvas） */
    val swingComponent: Component?

    /** PlayerKernel 平台渲染面桥接：桌面返回 AWT Canvas */
    override val renderSurface: Any?
        get() = swingComponent

    /**
     * 设置视频面可见性（详情页被其他页面覆盖时隐藏让位，回前台恢复）。
     * mpv/vlc 渲染接管 HWND 后 AWT setVisible 失效，须由内核经原生 ShowWindow 实现。
     */
    override fun setSurfaceVisible(visible: Boolean) {}

    /** 内核是否已就绪可用 */
    override val isReady: Boolean

    /**
     * 播放新地址。[headers] 中的 UA/Referer 由内核属性注入；其余 header 已由
     * 调用方（PlaybackUrlResolver）包装为本地 /hdr 代理地址。
     */
    override fun play(url: String, headers: Map<String, String>, startMs: Long)

    /** 停止播放并复位状态 */
    override fun stop()

    /** 播放/暂停切换 */
    override fun togglePlayPause()

    /** 跳转进度（毫秒） */
    override fun seekTo(ms: Long)

    /** 设置倍速（1.0 为原速） */
    override fun setRate(rate: Float)

    /** 设置音量（0-100） */
    override fun setVolume(volume: Int)

    /** 即时切换画面比例（fit=原始尺寸 / crop=裁剪铺满 / stretch=拉伸画面） */
    override fun setVideoScale(scale: String)

    /** 截取当前帧保存为图片，返回文件路径；内核不支持或失败返回 null */
    override fun snapshot(): String?

    /** 释放 native 资源（窗口关闭时调用，不可重复） */
    override fun release()

    /**
     * 后台线程停止并释放内核：stop/release 涉及 native 拆卸（等待解码/网络线程退出、
     * 销毁内核句柄），耗时可达秒级；禁止在 EDT（Compose 组合销毁回调、Swing 事件）
     * 同步执行，否则返回/切页/关窗时整个应用卡顿。立即返回，资源由守护线程异步回收。
     */
    fun releaseAsync() {
        Thread {
            try {
                stop()
                release()
            } catch (t: Throwable) {
                AppLog.e("Player", "内核异步释放失败: ${t.message}\n${t.stackTraceToString()}")
            }
        }.apply {
            name = "player-release"
            isDaemon = true
        }.start()
    }
}

/** 播放内核类型（设置页可选，持久化于 DesktopPrefs） */
enum class PlayerKernel(val id: String, val displayName: String) {
    MPV("mpv", "mpv"),
    VLC("vlc", "VLC"),
    MDK("mdk", "MDK"),
    ;

    companion object {
        /** 按 id 解析内核，未知值回退 mpv */
        fun fromId(raw: String): PlayerKernel = entries.firstOrNull { it.id == raw } ?: MPV
    }
}

/** 内核工厂：按设置创建对应播放控制器 */
object PlayerControllerFactory {

    /** 内核偏好键（DesktopPrefs 持久化） */
    const val KEY_PLAYER_KERNEL = "playerKernel"

    fun fromPrefs(prefs: DesktopPrefs): PlayerKernel =
        PlayerKernel.fromId(prefs.getString(KEY_PLAYER_KERNEL, PlayerKernel.MPV.id))

    fun create(kernel: PlayerKernel, prefs: DesktopPrefs): PlayerController = when (kernel) {
        PlayerKernel.MPV -> MpvPlayerController(prefs)
        PlayerKernel.VLC -> VlcPlayerController(prefs)
        PlayerKernel.MDK -> MdkPlayerController(prefs)
    }
}

/**
 * 片头记录：按 影片+源+集 记录片头结束点（「记录片头」按钮），起播时自动跳过。
 * 键值存 DesktopPrefs，键用 hashCode 规避特殊字符与超长键名。
 */
object IntroMark {

    /** 片头记录目标（一部影片的某源某集） */
    data class Mark(val vodId: Int, val sourceUrl: String, val episodeName: String)

    private fun key(mark: Mark): String =
        "introEnd.${mark.vodId}.${mark.sourceUrl.hashCode()}.${mark.episodeName.hashCode()}"

    /** 记录片头结束点（毫秒） */
    fun save(prefs: DesktopPrefs, mark: Mark, endMs: Long) {
        prefs.put(key(mark), endMs)
    }

    /** 读取片头结束点（未记录返回 0） */
    fun endMs(prefs: DesktopPrefs, mark: Mark): Long = prefs.getLong(key(mark), 0L)
}

/**
 * 播放地址解析（SpiderPlayInfo -> 内核可直接播放的地址）：
 * - 无 header 或仅 UA/Referer：原样直链（内核属性覆盖）
 * - 含 Cookie 等其他 header：包装为本地代理 /hdr 地址流式转发
 */
object PlaybackUrlResolver {

    /** 需要 /hdr 代理转发的 header 键（UA/Referer 之外的都算） */
    fun needsHdrProxy(headers: Map<String, String>): Boolean = headers.keys.any { key ->
        !key.equals("User-Agent", true) && !key.equals("Referer", true)
    }

    /** 包装为 /hdr 代理地址（port 为本地代理端口） */
    fun wrapHdrProxy(url: String, headers: Map<String, String>, port: Int): String {
        val headerText = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
        val encodedUrl = urlEncode(url)
        val encodedHeaders = urlEncode(headerText)
        return "http://127.0.0.1:$port/hdr?url=$encodedUrl&h=$encodedHeaders"
    }

    /** 生成完整播放地址：按需包装 /hdr（headers 含 UA/Referer 之外字段时） */
    fun resolve(playUrl: String, headers: Map<String, String>, proxyPort: Int): String =
        if (needsHdrProxy(headers)) wrapHdrProxy(playUrl, headers, proxyPort) else playUrl

    /**
     * URL 编码：URLEncoder 的空格转义为 +，NanoHTTPD 不解 + 为空格，
     * 统一替换为 %20 保证两端一致。
     */
    private fun urlEncode(raw: String): String =
        URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")
}

/** 倍速展示格式（0.5 -> 0.5x，1.0 -> 1.0x） */
fun formatSpeed(rate: Float): String = "${"%.1f".format(rate)}x"

/** 时长/进度格式化（mm:ss 或 h:mm:ss） */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** 判断是否为直链（m3u8/mp4/flv/ts），parse=1 的网页地址需要解析不支持 */
fun isDirectStreamUrl(url: String): Boolean {
    val path = url.substringBefore("?").lowercase()
    return Constants.DIRECT_STREAM_SUFFIXES.any { path.endsWith(it) }
}
