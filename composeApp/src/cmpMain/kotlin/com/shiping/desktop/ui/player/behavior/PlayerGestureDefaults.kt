package com.shiping.desktop.ui.player.behavior

/**
 * 播放行为共享常量与反馈文案（Android/桌面收口）：
 * 两端一致的取值唯一来源；平台差异阈值经 PlayerGestureSession.Options 参数化。
 */

/** 控制层自动隐藏延迟（播放中无操作毫秒数；Android LaunchedEffect / 桌面 Swing Timer 共用） */
const val CONTROLS_AUTO_HIDE_MS = 3000L

/** 手势横向每像素快进毫秒（对齐两端 Constants.GESTURE_SEEK_MS_PER_PX） */
const val GESTURE_SEEK_MS_PER_PX = 1000f

/** 手势单次最大快进毫秒（对齐两端 Constants.GESTURE_SEEK_MAX_MS） */
const val GESTURE_SEEK_MAX_MS = 600_000L

/** 桌面手势方向判定阈值（像素直判；鼠标无触摸惯性，取小值） */
const val GESTURE_DECIDE_PX_DESKTOP = 12f

/** 桌面进度手势取消区半径（像素） */
const val GESTURE_CANCEL_PX_DESKTOP = 48f

/** 手势/倍速/静音反馈文案（两端展示口径统一） */
object GestureText {
    const val SEEK_CANCEL_HINT = "松手取消调整进度"
    const val SEEK_CANCELLED = "已取消调整进度"
    const val SEEK_FORWARD = "▶▶"
    const val SEEK_BACKWARD = "◀◀"
    const val MUTED = "已静音"
    const val UNMUTED = "已取消静音"

    /** 亮度百分比提示 */
    fun brightness(percent: Int): String = "亮度 $percent%"

    /** 音量百分比提示 */
    fun volume(percent: Int): String = "音量 $percent%"

    /** 倍速档位提示 */
    fun speed(rate: Float): String = "倍速 x$rate"

    /** 长按倍速提示 */
    fun speedBoost(rate: Float): String = "倍速 x$rate ▶▶"
}
