package com.shiping.desktop.ui.player

/**
 * 模式 UI 配置：控制层按此渲染形态（进度/时长/选集/频道列表显隐、手势允许集、布局密度）。
 * 内容类配置仍由 InPagePlayerConfig 承载，本类只管「形态」。
 */
data class ModeUiConfig(
    val showControls: Boolean,
    val showProgress: Boolean,
    val showDuration: Boolean,
    val showEpisodePanel: Boolean,
    val showChannelList: Boolean,
    val allowSeek: Boolean,
    val allowGesture: Boolean,
    val compact: Boolean,
)

/** 五模式配置表：差异全部枚举于此，新增形态先扩字段再补行（数据驱动，替代五策略类） */
private val MODE_CONFIGS: Map<PlayerMode, ModeUiConfig> = mapOf(
    PlayerMode.INLINE to ModeUiConfig(
        showControls = true,
        showProgress = true,
        showDuration = true,
        showEpisodePanel = true,
        showChannelList = false,
        allowSeek = true,
        allowGesture = true,
        compact = true,
    ),
    PlayerMode.FULLSCREEN to ModeUiConfig(
        showControls = true,
        showProgress = true,
        showDuration = true,
        showEpisodePanel = true,
        showChannelList = false,
        allowSeek = true,
        allowGesture = true,
        compact = false,
    ),
    PlayerMode.PIP to ModeUiConfig(
        showControls = false,
        showProgress = false,
        showDuration = false,
        showEpisodePanel = false,
        showChannelList = false,
        allowSeek = false,
        allowGesture = false,
        compact = true,
    ),
    PlayerMode.LIVE_INLINE to ModeUiConfig(
        showControls = true,
        showProgress = false,
        showDuration = false,
        showEpisodePanel = false,
        showChannelList = true,
        allowSeek = false,
        allowGesture = true,
        compact = true,
    ),
    PlayerMode.LIVE_FULLSCREEN to ModeUiConfig(
        showControls = true,
        showProgress = false,
        showDuration = false,
        showEpisodePanel = false,
        showChannelList = true,
        allowSeek = false,
        allowGesture = true,
        compact = false,
    ),
)

/** 取模式对应 UI 配置（表未覆盖即编程错误，直接抛出） */
fun configFor(mode: PlayerMode): ModeUiConfig = MODE_CONFIGS.getValue(mode)
