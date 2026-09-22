package com.shiping.shared.player

import kotlinx.coroutines.flow.StateFlow

/**
 * 播放内核统一接口（commonMain，形状对齐桌面 PlayerController）：
 * 桌面端实现为 mpv/vlc/MDK（JNA/vlcj，AWT Canvas 渲染）；
 * Android 端（M4）为 media3 ExoPlayer 薄适配。
 * 平台渲染面以 [renderSurface]: Any? 隔离——桌面为 AWT Component（SwingPanel 嵌入），
 * Android 为 null（PlayerView 经 AndroidView 渲染，不经此传递）。
 */
interface PlayerKernel {

    /** 是否正在播放 */
    val isPlaying: StateFlow<Boolean>

    /** 当前播放进度（毫秒） */
    val timeMs: StateFlow<Long>

    /** 媒体总时长（毫秒） */
    val durationMs: StateFlow<Long>

    /** 视频分辨率宽（像素，未知为 0） */
    val videoWidth: StateFlow<Int>

    /** 视频分辨率高（像素，未知为 0） */
    val videoHeight: StateFlow<Int>

    /** 网络缓冲中（画面卡顿加载） */
    val isBuffering: StateFlow<Boolean>

    /** 播放期错误提示（非空时控制条展示） */
    val errorText: StateFlow<String?>

    /** 内核初始化失败原因（缺 dll/so 等），非空时展示引导文案 */
    val initError: StateFlow<String?>

    /** 平台渲染面（桌面=AWT Component；Android=null） */
    val renderSurface: Any?

    /** 内核是否已就绪可用 */
    val isReady: Boolean

    /**
     * 播放新地址。[headers] 中的 UA/Referer 由内核属性注入；其余 header 已由
     * 调用方（PlaybackUrlResolver）包装为本地 /hdr 代理地址。
     */
    fun play(url: String, headers: Map<String, String>, startMs: Long)

    /** 停止播放并复位状态 */
    fun stop()

    /** 播放/暂停切换 */
    fun togglePlayPause()

    /** 跳转进度（毫秒） */
    fun seekTo(ms: Long)

    /** 设置倍速（1.0 为原速） */
    fun setRate(rate: Float)

    /** 设置音量（0-100） */
    fun setVolume(volume: Int)

    /** 即时切换画面比例（fit=原始尺寸 / crop=裁剪铺满 / stretch=拉伸画面） */
    fun setVideoScale(scale: String)

    /** 截取当前帧保存为图片，返回文件路径；内核不支持或失败返回 null */
    fun snapshot(): String?

    /** 释放 native 资源（窗口关闭时调用，不可重复） */
    fun release()

    /**
     * 设置视频面可见性（页面被覆盖时隐藏让位，回前台恢复）。
     * 桌面 mpv/vlc 渲染接管 HWND 后 AWT setVisible 失效，须由内核经原生 ShowWindow 实现。
     */
    fun setSurfaceVisible(visible: Boolean) {}
}
