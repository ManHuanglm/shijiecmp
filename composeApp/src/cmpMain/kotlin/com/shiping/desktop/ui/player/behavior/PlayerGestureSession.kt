package com.shiping.desktop.ui.player.behavior

import kotlin.math.abs

/**
 * 视频面拖动手势会话（Android/桌面共享，原两端各写一份）：
 * 锁向判定（横向调进度 / 左半屏竖向调亮度 / 右半屏竖向调音量）、
 * 进度目标计算（含松手取消）、亮度/音量百分比换算与反馈文案。
 * 平台层只喂原始位移并应用反馈结果；锁定态由平台层短路，不入会话。
 */
class PlayerGestureSession(private val options: Options) {

    /**
     * 手势参数（平台差异：Android 阈值由 dp 换算且启用亮度；
     * 桌面像素直判、无亮度、取消半径更大）
     */
    data class Options(
        /** 方向判定阈值（像素，累计位移超过才认定模式） */
        val decidePx: Float,
        /** 进度手势取消区半径（像素，拖回起点附近松手取消） */
        val cancelPx: Float,
        /** 横向每像素快进毫秒 */
        val seekMsPerPx: Float,
        /** 单次手势最大快进毫秒 */
        val seekMaxMs: Long,
        /** 是否启用左半屏亮度手势（桌面无亮度概念） */
        val enableBrightness: Boolean,
        /** 是否启用「松手取消调整进度」（设置开关） */
        val enableSeekCancel: Boolean,
    )

    /** 拖动中的实时反馈（平台层展示文案并应用值） */
    sealed interface Feedback {
        /** 进度实时提示（尚未生效，松手才 seek） */
        data class Seek(val targetMs: Long, val text: String) : Feedback

        /** 亮度即时值（0.01-1，平台层写 window.screenBrightness） */
        data class Brightness(val value: Float, val text: String) : Feedback

        /** 音量目标百分比（平台层经 MuteSession 钳制生效） */
        data class Volume(val percent: Int, val text: String) : Feedback
    }

    /** 松手提交动作 */
    sealed interface Commit {
        /** 按拖动终点跳转进度 */
        data class Seek(val targetMs: Long) : Commit

        /** 拖回起点附近松手：取消本次进度调整 */
        data object CancelSeek : Commit
    }

    /** 拖动方向：横向进度 / 左半屏亮度 / 右半屏音量 */
    private enum class DragMode { SEEK, BRIGHTNESS, VOLUME }

    private var dragMode: DragMode? = null
    private var startX = 0f
    private var width = 1f
    private var height = 1f
    private var dxAccum = 0f
    private var dyAccum = 0f
    private var seekBaseMs = 0L
    private var seekTargetMs = -1L
    private var seekMovedBeyondCancel = false
    private var volumeBase = 100
    private var brightnessBase = 0.5f

    /** 开始一次拖动（记录起点半屏归属与进度/音量/亮度基准值） */
    fun start(
        startX: Float,
        width: Float,
        height: Float,
        seekBaseMs: Long,
        volumeBase: Int,
        brightnessBase: Float,
    ) {
        this.startX = startX
        this.width = width.coerceAtLeast(1f)
        this.height = height.coerceAtLeast(1f)
        this.seekBaseMs = seekBaseMs
        this.volumeBase = volumeBase
        this.brightnessBase = brightnessBase
        dxAccum = 0f
        dyAccum = 0f
        dragMode = null
        seekTargetMs = -1L
        seekMovedBeyondCancel = false
    }

    /**
     * 累计位移推进手势（[durationMs] 实时传入；直播/起播前为 0 时进度方向不锁定）。
     * 返回本次反馈；方向未判定时返回 null。
     */
    fun onDelta(dx: Float, dy: Float, durationMs: Long): Feedback? {
        dxAccum += dx
        dyAccum += dy
        if (dragMode == null) {
            dragMode = when {
                abs(dxAccum) > options.decidePx && abs(dxAccum) >= abs(dyAccum) -> DragMode.SEEK
                abs(dyAccum) > options.decidePx ->
                    if (options.enableBrightness && startX < width / 2f) DragMode.BRIGHTNESS else DragMode.VOLUME
                else -> return null
            }
        }
        return when (dragMode) {
            DragMode.SEEK -> onSeekDelta(durationMs)
            DragMode.BRIGHTNESS -> onBrightnessDelta()
            DragMode.VOLUME -> onVolumeDelta()
            null -> null
        }
    }

    /** 松手提交：进度方向返回 seek/取消；亮度音量已即时生效，返回 null */
    fun onEnd(): Commit? {
        val mode = dragMode
        dragMode = null
        if (mode != DragMode.SEEK) return null
        val canCancel = options.enableSeekCancel && seekMovedBeyondCancel && abs(dxAccum) <= options.cancelPx
        val target = seekTargetMs
        seekTargetMs = -1L
        return when {
            canCancel -> Commit.CancelSeek
            target >= 0L -> Commit.Seek(target)
            else -> null
        }
    }

    private fun onSeekDelta(durationMs: Long): Feedback? {
        if (durationMs <= 0L) {
            dragMode = null
            return null
        }
        if (abs(dxAccum) > options.cancelPx) seekMovedBeyondCancel = true
        val rawTarget = seekBaseMs + (dxAccum * options.seekMsPerPx).toLong()
        seekTargetMs = rawTarget.coerceIn(0L, minOf(durationMs, seekBaseMs + options.seekMaxMs))
        val canCancel = options.enableSeekCancel && seekMovedBeyondCancel && abs(dxAccum) <= options.cancelPx
        val text = if (canCancel) {
            GestureText.SEEK_CANCEL_HINT
        } else {
            val arrow = if (rawTarget >= seekBaseMs) GestureText.SEEK_FORWARD else GestureText.SEEK_BACKWARD
            "$arrow ${formatGestureTime(seekTargetMs)} / ${formatGestureTime(durationMs)}"
        }
        return Feedback.Seek(seekTargetMs, text)
    }

    private fun onBrightnessDelta(): Feedback {
        val value = (brightnessBase - dyAccum / height).coerceIn(0.01f, 1f)
        return Feedback.Brightness(value, GestureText.brightness((value * 100).toInt()))
    }

    private fun onVolumeDelta(): Feedback {
        val percent = volumeBase + (-dyAccum / height * 100).toInt()
        return Feedback.Volume(percent, GestureText.volume(percent.coerceIn(0, 100)))
    }

    /** 毫秒转 h:mm:ss / m:ss（手势进度提示） */
    private fun formatGestureTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
