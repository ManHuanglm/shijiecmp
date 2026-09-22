package com.shiping.desktop.ui

import com.shiping.shared.prefs.KeyValueStore

/** 页内播放控制层宿主（桌面=PlayerOverlay 实现，Android=Compose 控制层实现） */
interface InPageOverlayHost {

    /** 控制层显隐：切页时命令式隐藏视频面与悬浮层，防遮挡其它页面 */
    fun setBackground(hidden: Boolean)
}

/**
 * 独立直链播放宿主（桌面=PlayerFrame 弹窗；Android=全屏播放页路由）。
 * 局域网推送直链等无详情上下文的播放经此承载（inPage=false 会话）。
 */
interface DirectPlaybackHost {

    /**
     * 打开（或复用存活窗口）按会话起播。
     * @param kernelId 内核 ID 字符串（桌面 mpv/vlc/mdk，Android media3），实现方自行解析
     */
    fun openOrCreate(session: PlaybackSession, kernelId: String, prefs: KeyValueStore)

    /** 关闭播放宿主（幂等） */
    fun close()
}

/** 内核 ID 常量（cmpMain 通用字符串；桌面枚举与 Android 实现各自解析） */
object KernelIds {
    const val MPV = "mpv"
    const val VLC = "vlc"
    const val MDK = "mdk"
    const val MEDIA3 = "media3"
    const val DEFAULT = MPV
}
