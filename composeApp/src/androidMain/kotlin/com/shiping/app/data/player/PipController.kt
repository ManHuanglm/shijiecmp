package com.shiping.app.data.player

import android.app.Activity
import android.os.Build
import android.util.Rational
import androidx.core.math.MathUtils
import com.shiping.shared.player.PlayerKernel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 画中画控制器：播放中离开应用（Home/切任务）自动进入 PiP 小窗续播。
 * - 内页播放面/直链播放页挂载当前内核，onUserLeaveHint 时校验播放态后 enterPictureInPictureMode
 * - isInPip 供控制层隐藏覆盖 UI（小窗中只留画面）
 */
object PipController {

    /** 系统允许的宽高比范围（2.39:1 ~ 1:2.39） */
    private const val MAX_RATIO = 2.39f
    private const val MIN_RATIO = 1f / 2.39f
    private const val FALLBACK_RATIO = 16f / 9f

    private val _isInPip = MutableStateFlow(false)
    val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

    /** 当前 PiP 候选内核（播放组合挂载、销毁卸载；直链播放页同样挂载） */
    @Volatile
    private var activeKernel: PlayerKernel? = null

    /** 挂载内核（页内播放面组合时调用） */
    fun attachKernel(kernel: PlayerKernel) {
        activeKernel = kernel
    }

    /** 卸载内核（仅卸载自己挂载的，防新页内核被旧页销毁误卸） */
    fun detachKernel(kernel: PlayerKernel) {
        if (activeKernel === kernel) activeKernel = null
    }

    /** Activity PiP 模式变化回调转发 */
    fun onPipModeChanged(inPip: Boolean) {
        _isInPip.value = inPip
    }

    /** 尝试进入画中画：Android O+ 且内核正在播放；失败返回 false（正常走后台） */
    fun tryEnterPip(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val kernel = activeKernel ?: return false
        if (!kernel.isPlaying.value) return false
        val aspect = buildAspectRatio(kernel.videoWidth.value, kernel.videoHeight.value)
        return runCatching {
            activity.enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(aspect).build(),
            )
        }.getOrDefault(false)
    }

    /** 视频宽高比（越界截断到系统允许区间；未知尺寸回退 16:9） */
    private fun buildAspectRatio(width: Int, height: Int): Rational {
        val ratio = if (width > 0 && height > 0) width.toFloat() / height else FALLBACK_RATIO
        val clamped = MathUtils.clamp(ratio, MIN_RATIO, MAX_RATIO)
        return Rational((clamped * 100).roundToInt(), 100)
    }
}
