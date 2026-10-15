package com.shiping.desktop.ui.player

/**
 * 统一播放模式（参考五模式方案适配本项目）：
 * 页内播放器（详情页点播 / 直播页）共用同一模式语义。
 * 全屏标志保留布局真源（AppViewModel.detailFullscreen/tvFullscreen），本枚举为派生视图。
 */
enum class PlayerMode(
    val isLive: Boolean,
    val isFullscreen: Boolean,
) {
    /** 点播页内嵌入（竖屏 16:9 内嵌形态） */
    INLINE(isLive = false, isFullscreen = false),

    /** 点播全屏（铺满应用窗口） */
    FULLSCREEN(isLive = false, isFullscreen = true),

    /** 系统画中画（仅 Android；只留画面，无控制层） */
    PIP(isLive = false, isFullscreen = false),

    /** 直播页内嵌入（播放区 + 频道列表布局） */
    LIVE_INLINE(isLive = true, isFullscreen = false),

    /** 直播全屏 */
    LIVE_FULLSCREEN(isLive = true, isFullscreen = true),
    ;

    /** 是否显示进度条（直播无进度概念，画中画无控制层） */
    val showProgress: Boolean get() = configFor(this).showProgress

    /** 是否显示频道列表入口 */
    val showChannelList: Boolean get() = configFor(this).showChannelList
}
