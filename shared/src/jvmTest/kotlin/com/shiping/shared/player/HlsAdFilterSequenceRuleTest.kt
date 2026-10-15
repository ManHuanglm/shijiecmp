package com.shiping.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HlsAdFilter 规则四（切片序号连续性）单元测试：
 * 场景取自 lzs 系 mixed.m3u8 真实结构——正片序号连续编号，中插广告为独立序号序列，
 * 同目录、时长相近，前三条规则（关键词/时长分块/目录指纹）均不命中。
 */
class HlsAdFilterSequenceRuleTest {

    /** 组装媒体级播放列表：每个子列表为一个 DISCONTINUITY 分块 */
    private fun playlist(blocks: List<List<Pair<Float, String>>>): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:8")
        blocks.forEachIndexed { index, segments ->
            if (index > 0) appendLine("#EXT-X-DISCONTINUITY")
            segments.forEach { (duration, uri) ->
                appendLine("#EXTINF:$duration,")
                appendLine(uri)
            }
        }
    }

    /** 生成连续编号切片块：prefix + 序号（前导零补齐）.ts */
    private fun seq(
        prefix: String,
        from: Int,
        count: Int,
        duration: Float = 6f,
        pad: Int = 6,
    ): List<Pair<Float, String>> =
        (0 until count).map { duration to "$prefix${(from + it).toString().padStart(pad, '0')}.ts" }

    @Test
    fun 中插广告序号脱节且后侧回归时移除() {
        // lzs 真实结构：正片 000~019 连续，广告 348888 起步独立序列（同目录、时长相近）
        val result = HlsAdFilter.filter(
            playlist(
                listOf(
                    seq("8dac599d7cc", 0, 5),
                    seq("8dac599d7cc", 5, 5),
                    seq("8dac599d7cc", 348888, 7, duration = 5f),
                    seq("8dac599d7cc", 10, 5),
                    seq("8dac599d7cc", 15, 5),
                ),
            ),
        )
        assertEquals(7, result.removedSegments, "广告块 7 片应被序号规则移除")
        assertEquals(false, result.text.contains("348888"), "广告切片应全部移除")
        assertEquals(true, result.text.contains("000010.ts"), "回归正片切片应保留")
    }

    @Test
    fun 前置广告经主块向前锚定移除() {
        val result = HlsAdFilter.filter(
            playlist(
                listOf(
                    seq("vod", 900001, 3, duration = 5f, pad = 6),
                    seq("vod", 0, 10),
                    seq("vod", 10, 10),
                ),
            ),
        )
        assertEquals(3, result.removedSegments, "前置广告块应被移除")
        assertEquals(false, result.text.contains("900001"), "前置广告切片应全部移除")
    }

    @Test
    fun 序号跳跃无回归时保守保留() {
        // 主块之后全部偏离且无回归点，无法证明可安全删除（可能为正片丢号），应全部保留
        val result = HlsAdFilter.filter(
            playlist(
                listOf(
                    seq("vod", 0, 10),
                    seq("vod", 100, 10),
                    seq("vod", 200, 10),
                ),
            ),
        )
        assertEquals(0, result.removedSegments, "无回归确认时不应删除任何块")
    }

    @Test
    fun 不可判定块中断延伸时保守保留() {
        // 无数字文件名块中断向后延伸：其后正片不参与判定，整体保守保留
        val result = HlsAdFilter.filter(
            playlist(
                listOf(
                    seq("vod", 0, 10),
                    listOf(5f to "hash-a.ts", 5f to "hash-b.ts"),
                    seq("vod", 10, 10),
                ),
            ),
        )
        assertEquals(0, result.removedSegments, "含不可判定块时不应启用序号规则")
    }

    @Test
    fun 中插回归后再次偏离的块一并移除() {
        val result = HlsAdFilter.filter(
            playlist(
                listOf(
                    seq("vod", 0, 10),
                    seq("ad", 700001, 3, duration = 5f, pad = 6),
                    seq("vod", 10, 10),
                    seq("ad", 800001, 2, duration = 5f, pad = 6),
                ),
            ),
        )
        assertEquals(5, result.removedSegments, "两处广告块（3+2 片）均应移除")
    }
}
