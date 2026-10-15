package com.shiping.desktop.ui.player.behavior

/**
 * 会话级静音（两端共享，原两端各写一份）：静音=音量 0，解除恢复静音前音量；
 * 手势/滑杆调高音量（>0）自动解除静音。
 * 音量落地经 [applyVolume] 回调（Android=内核 setVolume，桌面=setSessionVolume）；
 * UI 状态同步经 [onMuteChanged]（Compose state / Swing 标签各自承接）。
 */
class MuteSession(private val applyVolume: (Int) -> Unit) {

    /** 静音前音量（解除静音恢复用） */
    private var volumeBeforeMute: Int = 100

    /** 当前是否静音 */
    var isMuted: Boolean = false
        private set

    /** 静音状态变化回调（UI 层同步展示状态） */
    var onMuteChanged: ((Boolean) -> Unit)? = null

    /** 切换静音（[currentVolume] 为切换前的会话音量） */
    fun toggle(currentVolume: Int) {
        setMuted(!isMuted, currentVolume)
    }

    /** 设置静音（幂等） */
    fun setMuted(muted: Boolean, currentVolume: Int) {
        if (isMuted == muted) return
        isMuted = muted
        if (muted) {
            volumeBeforeMute = currentVolume
            applyVolume(0)
        } else {
            applyVolume(volumeBeforeMute)
        }
        onMuteChanged?.invoke(muted)
    }

    /**
     * 手势/滑杆调整音量（0-100 即时生效），返回钳制后的实际音量；
     * 调高音量顺带解除静音。
     */
    fun onVolumeAdjusted(percent: Int): Int {
        val clamped = percent.coerceIn(MIN_VOLUME, MAX_VOLUME)
        if (clamped > MIN_VOLUME && isMuted) {
            isMuted = false
            onMuteChanged?.invoke(false)
        }
        applyVolume(clamped)
        return clamped
    }

    private companion object {
        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 100
    }
}
