package com.shiping.desktop.ui.player.behavior

/**
 * 倍速档位循环（两端共享）：内核不回读速率，由 UI 层本地记录当前档位。
 * 档位表由平台传入（Android 内嵌 6 档 / 桌面按 Constants.PLAYBACK_SPEEDS）。
 */
class RateCycle(private val steps: List<Float>) {

    private var index: Int = defaultIndex()

    /** 当前档位速率 */
    val current: Float get() = steps[index]

    /** 以指定速率就位（未知速率回落 1.0 档，如全局倍速设置不在表内） */
    fun resetTo(rate: Float) {
        index = steps.indexOf(rate).takeIf { it >= 0 } ?: defaultIndex()
    }

    /** 循环至下一档并返回速率 */
    fun next(): Float {
        index = (index + 1).mod(steps.size)
        return steps[index]
    }

    private fun defaultIndex(): Int = steps.indexOf(1.0f).coerceAtLeast(0)
}
