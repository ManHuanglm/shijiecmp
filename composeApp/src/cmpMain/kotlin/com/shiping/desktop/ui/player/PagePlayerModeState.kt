package com.shiping.desktop.ui.player

import com.shiping.desktop.ui.PagePlayerScope
import com.shiping.shared.player.PlayerKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 页内播放统一状态机（页面局部，Detail/Tv 各持一个）：
 * bind 后 collect 内核状态流与页面全屏/PiP 标志，投影为统一 [PlayerUiState]。
 * 不拥有内核——起播/停止/释放仍由页面编排（Detail 的 awaitSurfaceReady→play、
 * Tv 的 failoverPlay），unbind 仅解除观察并复位状态。
 */
class PagePlayerModeState(
    private val observerScope: CoroutineScope,
    private val fullscreen: StateFlow<Boolean>,
) {

    /** 内核状态快照（五流合一，规避多类型 vararg combine 的装箱） */
    private data class KernelSnapshot(
        val isPlaying: Boolean,
        val isBuffering: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val errorText: String?,
    )

    private val pipActive = MutableStateFlow(false)
    private var collectJob: Job? = null
    private var pageScope: PagePlayerScope = PagePlayerScope.DETAIL

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** 当前模式（未绑定时为默认 INLINE） */
    val mode: PlayerMode get() = _uiState.value.mode

    /** 内核创建后绑定观察；重复绑定先解除旧观察（换内核场景） */
    fun bind(kernel: PlayerKernel, scope: PagePlayerScope) {
        unbind()
        pageScope = scope
        collectJob = observerScope.launch {
            combine(
                combine(
                    kernel.isPlaying,
                    kernel.isBuffering,
                    kernel.timeMs,
                    kernel.durationMs,
                    kernel.errorText,
                ) { isPlaying, isBuffering, positionMs, durationMs, errorText ->
                    KernelSnapshot(isPlaying, isBuffering, positionMs, durationMs, errorText)
                },
                fullscreen,
                pipActive,
            ) { snapshot, fullscreenOn, pip ->
                PlayerUiState(
                    isPlaying = snapshot.isPlaying,
                    isBuffering = snapshot.isBuffering,
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    errorText = snapshot.errorText,
                    mode = inferMode(scope, fullscreenOn, pip),
                )
            }.collect { _uiState.value = it }
        }
    }

    /** 解除观察并复位状态（页面退出播放态/组合销毁时调用） */
    fun unbind() {
        collectJob?.cancel()
        collectJob = null
        pipActive.value = false
        _uiState.value = PlayerUiState()
    }

    /** 系统画中画开关（仅 Android 有意义；进入/退出 PiP 时同步） */
    fun setPip(active: Boolean) {
        pipActive.value = active
    }
}
