package com.shiping.shared.download

import kotlin.math.max
import kotlin.math.min

/**
 * 令牌窗限速器：滑动窗口内累计字节数超过配额时阻塞补齐。
 * 供下载写循环调用（阻塞 IO 上下文），[bytesPerSecond] <= 0 表示不限速直通。
 */
class SpeedLimiter(private val bytesPerSecond: Long) {

    companion object {
        /** 单次休眠上限（毫秒），切片休眠保证暂停/取消能及时介入 */
        private const val MAX_SLEEP_MS = 200L
    }

    /** 窗口起点（纳秒） */
    private var windowStartNanos = 0L

    /** 窗口内已发放字节数 */
    private var windowBytes = 0L

    /** 累计发放 [bytes] 字节；超出当前窗口配额时按剩余时间切片休眠 */
    fun acquire(bytes: Int) {
        if (bytesPerSecond <= 0 || bytes <= 0) return
        val now = System.nanoTime()
        if (windowStartNanos == 0L) {
            windowStartNanos = now
        }
        windowBytes += bytes
        val windowNanos = windowBytes * 1_000_000_000L / bytesPerSecond
        val deadline = windowStartNanos + windowNanos
        var remainNanos = deadline - now
        if (remainNanos <= 0) {
            // 已超配额：窗口滞后过多时重置窗口防累积休眠雪崩
            if (-remainNanos > 1_000_000_000L) {
                windowStartNanos = now
                windowBytes = bytes.toLong()
            }
            return
        }
        while (remainNanos > 0) {
            val sleepMs = min(MAX_SLEEP_MS, max(1L, remainNanos / 1_000_000L))
            Thread.sleep(sleepMs)
            remainNanos = deadline - System.nanoTime()
        }
    }
}
