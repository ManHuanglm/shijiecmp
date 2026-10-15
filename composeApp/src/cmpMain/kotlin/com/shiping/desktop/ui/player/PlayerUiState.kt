package com.shiping.desktop.ui.player

/**
 * 统一播放状态（内核只读投影）：控制层消费的单一状态源。
 * 不含写侧——播放控制仍直达 PlayerKernel，避免重复造状态写入口。
 */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorText: String? = null,
    val mode: PlayerMode = PlayerMode.INLINE,
) {
    /** 当前模式 UI 配置 */
    val config: ModeUiConfig get() = configFor(mode)

    /** 是否直播模式 */
    val isLive: Boolean get() = mode.isLive
}
