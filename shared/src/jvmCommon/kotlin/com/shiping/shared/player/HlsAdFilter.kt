package com.shiping.shared.player

/**
 * m3u8 播放列表去广告过滤器（jvmCommon 共享核心：Android media3 数据源与桌面本地代理 /m3u8 路由共用）。
 *
 * 识别规则：
 * 1. 关键词：切片 URL 命中 [AD_URL_KEYWORDS] 黑名单 → 广告
 * 2. 时长分块启发式：按 #EXT-X-DISCONTINUITY 将切片分块，与主内容块（总时长最长）相比，
 *    平均切片时长偏差大（比值 < [AD_DURATION_RATIO_MIN] 或 > [AD_DURATION_RATIO_MAX]）
 *    且块总时长 <= [AD_BLOCK_MAX_TOTAL_S] 的块 → 广告
 * 3. 目录指纹：非主块且块总时长 <= [AD_BLOCK_MAX_TOTAL_S]，
 *    其切片目录与主块主目录不同 → 广告
 *    （modujx 系典型：正片 /20260710/xxx/2000kb/，广告 /20260831/xxx/10092kb/，
 *    即使广告切片时长与正片接近也能命中）
 * 4. 序号连续性：切片文件名尾部数字序号沿主块两端连续衔接，序号脱节且另一侧
 *    重新衔接主链的孤立块 → 广告（向前方向主块即锚点直接判）
 *    （lzs 系 mixed.m3u8 典型：正片 8dac599d7cc000000~000087 连续编号，
 *    中插广告 8dac599d7cc0034888 起步独立序列，同目录同时长，前三条规则全不命中）
 *
 * 安全策略：
 * - 主播放列表（#EXT-X-STREAM-INF，多清晰度）不过滤，避免误删清晰度条目
 * - 广告块内的 #EXT-X-KEY/#EXT-X-MAP 标签随切片一并移除，
 *   避免残留 METHOD=NONE 导致后续加密正片按明文解析而花屏
 * - 启发式候选块均须满足块总时长 <= [AD_BLOCK_MAX_TOTAL_S]（防误删大段正片）
 * - 序号规则向后方向需回归确认（删后主链能续上），不可判定块一律保留；
 *   块内含无法提取序号的切片则该块不参与判定
 * - 若判定结果会删掉全部切片，视为误判，放弃过滤返回原文
 */
object HlsAdFilter {

    /** m3u8 广告切片 URL 关键词（命中即为广告） */
    val AD_URL_KEYWORDS = listOf(
        "ad.", "ads.", "advert", "promo", "preroll",
        "/ad/", "/ads/", "/adverts", "/promoid",
        "adcdn", "advideo", "admaster", "adtech",
        "doubleclick", "googlesyndication", "analytics",
    )

    /** 分块启发式：块平均切片时长与主内容块比值低于该值视为广告块 */
    const val AD_DURATION_RATIO_MIN = 0.6f

    /** 分块启发式：块平均切片时长与主内容块比值高于该值视为广告块 */
    const val AD_DURATION_RATIO_MAX = 1.8f

    /** 分块启发式：候选广告块的最大总时长（秒），超过则认定为正片不删 */
    const val AD_BLOCK_MAX_TOTAL_S = 180

    /** 过滤结果 */
    data class Result(
        /** 过滤后的播放列表文本 */
        val text: String,
        /** 移除的切片数量 */
        val removedSegments: Int,
    )

    /** 切片条目：覆盖从切片标签（EXTINF 等）到 URI 行的行区间 */
    private class Segment(
        val startLine: Int,
        val endLine: Int,
        val uri: String,
        val durationSec: Float,
        val blockIndex: Int,
        /** 目录指纹（去 query/scheme/host 后的路径目录），用于规则三 */
        val dir: String,
    )

    fun filter(playlist: String): Result {
        // 非媒体级播放列表（主播放列表/无 EXTM3U 头）直接跳过
        if (!playlist.contains("#EXTM3U") || playlist.contains("#EXT-X-STREAM-INF")) {
            return Result(playlist, 0)
        }

        val lines = playlist.lines()
        val segments = parseSegments(lines)
        if (segments.isEmpty()) return Result(playlist, 0)

        val keywordAd = segments.map { isKeywordAd(it.uri) }
        val stats = collectBlockStats(segments)
        val blockAd = computeBlockAdFlags(segments, stats)
        val sequenceAd = computeSequenceAdFlags(segments, stats)

        val removedLines = HashSet<Int>()
        var removedCount = 0
        segments.forEachIndexed { index, segment ->
            if (keywordAd[index] || blockAd[index] || sequenceAd[index]) {
                removedCount++
                for (line in segment.startLine..segment.endLine) {
                    removedLines.add(line)
                }
            }
        }
        // 全删视为误判，放弃过滤
        if (removedCount == 0 || removedCount == segments.size) {
            return Result(playlist, 0)
        }

        val sb = StringBuilder(playlist.length)
        lines.forEachIndexed { index, line ->
            if (index !in removedLines) sb.append(line).append('\n')
        }
        return Result(sb.toString(), removedCount)
    }

    /** 解析媒体播放列表为切片条目列表 */
    private fun parseSegments(lines: List<String>): List<Segment> {
        val segments = mutableListOf<Segment>()
        var currentStart = -1
        var currentDuration = 0f
        var blockIndex = 0

        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            when {
                // 注意排除 #EXT-X-DISCONTINUITY-SEQUENCE（属于头部标签，不是分块边界）
                line.startsWith("#EXT-X-DISCONTINUITY") &&
                    !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> blockIndex++

                line.startsWith("#EXTINF") -> {
                    if (currentStart == -1) currentStart = index
                    currentDuration = line.removePrefix("#EXTINF:")
                        .substringBefore(',').trim().toFloatOrNull() ?: 0f
                }

                // 分块边界后的解密声明（如广告块的 METHOD=NONE）挂到下一个切片，
                // 切片被删时一并移除；文件头部的全局 KEY 声明（blockIndex==0）保留
                (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")) &&
                    blockIndex > 0 -> {
                    if (currentStart == -1) currentStart = index
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    // 切片 URI 行：条目区间从未写标签的行到本行
                    if (currentStart == -1) currentStart = index
                    segments.add(
                        Segment(currentStart, index, line, currentDuration, blockIndex, uriDirectory(line)),
                    )
                    currentStart = -1
                    currentDuration = 0f
                }
            }
        }
        return segments
    }

    /** 规则一：切片 URL 命中关键词黑名单 */
    private fun isKeywordAd(uri: String): Boolean {
        val lower = uri.lowercase()
        return AD_URL_KEYWORDS.any { lower.contains(it) }
    }

    /** 提取切片 URI 的目录指纹（去 query/scheme/host，仅保留路径目录部分） */
    private fun uriDirectory(uri: String): String {
        val noQuery = uri.substringBefore('?')
        val path = if (noQuery.contains("://")) {
            noQuery.substringAfter("://").substringAfter('/', missingDelimiterValue = "")
        } else {
            noQuery
        }
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash == -1) "" else path.substring(0, lastSlash)
    }

    /** 分块统计：各块总时长/切片数/主目录计数/可提取序号列表 */
    private class BlockStats(
        val blockTotal: FloatArray,
        val blockSegCount: IntArray,
        val blockDirCount: Array<HashMap<String, Int>>,
        val blockIndexes: List<List<Long>>,
    )

    /** 汇总分块统计（规则二/三/四共用一次遍历） */
    private fun collectBlockStats(segments: List<Segment>): BlockStats {
        val blockCount = segments.maxOf { it.blockIndex } + 1
        val totals = FloatArray(blockCount)
        val segCounts = IntArray(blockCount)
        val dirCounts = Array(blockCount) { HashMap<String, Int>() }
        val indexes = List(blockCount) { ArrayList<Long>() }
        for (segment in segments) {
            val block = segment.blockIndex
            totals[block] += segment.durationSec
            segCounts[block]++
            dirCounts[block].merge(segment.dir, 1, Int::plus)
            segmentIndex(segment.uri)?.let(indexes[block]::add)
        }
        return BlockStats(totals, segCounts, dirCounts, indexes)
    }

    /**
     * 主块选择：全部切片可提取序号时，选双向序号衔接块数最多的块作锚
     * （防止总时长最大的广告块被误选为主块，导致真删正片留广告），
     * 平分时取总时长更长者；存在不可判定切片时退回总时长最长。
     */
    private fun selectMainBlock(segments: List<Segment>, stats: BlockStats): Int {
        val allExtractable = segments.all { segmentIndex(it.uri) != null }
        var best = 0
        var bestScore = -1
        for (anchor in stats.blockTotal.indices) {
            if (allExtractable) {
                if (stats.blockIndexes[anchor].isEmpty()) continue  // 空块（首部 DISCONTINUITY）不可作锚
                val score = chainScore(anchor, stats.blockIndexes)
                val better = score > bestScore ||
                    (score == bestScore && stats.blockTotal[anchor] > stats.blockTotal[best])
                if (better) {
                    best = anchor
                    bestScore = score
                }
            } else if (stats.blockTotal[anchor] > stats.blockTotal[best]) {
                best = anchor
            }
        }
        return best
    }

    /** 以 anchor 为锚的双向衔接块数（脱节块跳过继续找回归，不可判定块中断） */
    private fun chainScore(anchor: Int, blockIndexes: List<List<Long>>): Int {
        var score = 0
        var expected = blockIndexes[anchor].last() + 1
        for (block in anchor + 1 until blockIndexes.size) {
            val indexes = blockIndexes[block]
            if (indexes.isEmpty()) break
            if (indexes.first() == expected) {
                expected = indexes.last() + 1
                score++
            }
        }
        expected = blockIndexes[anchor].first() - 1
        for (block in anchor - 1 downTo 0) {
            val indexes = blockIndexes[block]
            if (indexes.isEmpty()) break
            if (indexes.last() == expected) {
                expected = indexes.first() - 1
                score++
            }
        }
        return score
    }

    /** 规则二/三：分块启发式（时长偏差 + 目录指纹），返回每个切片是否属于广告块 */
    private fun computeBlockAdFlags(segments: List<Segment>, stats: BlockStats): BooleanArray {
        val flags = BooleanArray(segments.size)
        // 无分块或只有一个块时，没有对照主块，启发式不生效
        if (stats.blockTotal.size < 2) return flags

        val mainBlock = selectMainBlock(segments, stats)
        val mainAvg = stats.blockTotal[mainBlock] / stats.blockSegCount[mainBlock].coerceAtLeast(1)
        // 各块的主目录（出现最多的目录指纹）
        val blockDominantDir = Array(stats.blockDirCount.size) { block ->
            stats.blockDirCount[block].maxByOrNull { it.value }?.key.orEmpty()
        }
        val mainDir = blockDominantDir[mainBlock]

        segments.forEachIndexed { index, segment ->
            val block = segment.blockIndex
            if (block == mainBlock) return@forEachIndexed
            val durationAd = mainAvg > 0f && run {
                val ratio = stats.blockTotal[block] / stats.blockSegCount[block].coerceAtLeast(1) / mainAvg
                ratio < AD_DURATION_RATIO_MIN || ratio > AD_DURATION_RATIO_MAX
            }
            // 目录指纹：广告切片常与正片位于不同目录（不同日期/码率目录）
            val foreignDirAd = blockDominantDir[block] != mainDir
            if (stats.blockTotal[block] <= AD_BLOCK_MAX_TOTAL_S &&
                (durationAd || foreignDirAd)
            ) {
                flags[index] = true
            }
        }
        return flags
    }

    /**
     * 规则四：切片序号连续性。正片切片的文件名尾部数字序号与主块连续衔接；
     * 序号脱节的孤立块判广告——向后方向需另一侧重新衔接主链（回归确认），
     * 向前方向主块即回归锚点直接判。候选块叠加块总时长上限。
     */
    private fun computeSequenceAdFlags(segments: List<Segment>, stats: BlockStats): BooleanArray {
        val flags = BooleanArray(segments.size)
        val blockCount = stats.blockTotal.size
        // 少于 3 块构不成"偏离-回归"结构，不启用
        if (blockCount < 3) return flags

        val mainBlock = selectMainBlock(segments, stats)
        val mainIndexes = stats.blockIndexes[mainBlock]
        // 主块存在无法提取序号的切片 → 序号规则整体不生效（保守）
        if (mainIndexes.size != segments.count { it.blockIndex == mainBlock }) return flags

        val candidate = BooleanArray(blockCount)
        // 向后衔接：期望主块末序号+1；偏离记候选，任一后续块重新衔接即回归确认
        var expectedAfter = mainIndexes.last() + 1
        var returnedAfter = false
        for (block in mainBlock + 1 until blockCount) {
            val indexes = stats.blockIndexes[block]
            if (indexes.isEmpty()) break  // 不可判定块中断延伸，其候选在回归检查后撤销
            if (indexes.first() == expectedAfter) {
                expectedAfter = indexes.last() + 1
                returnedAfter = true
            } else {
                candidate[block] = true
            }
        }
        // 无回归点（主块之后全是偏离）无法证明可安全删除，撤销全部向后候选
        if (!returnedAfter) {
            for (block in mainBlock + 1 until blockCount) candidate[block] = false
        }
        // 向前衔接：期望主块首序号-1 倒序延伸；主块本身即回归锚点，偏离直接判
        var expectedBefore = mainIndexes.first() - 1
        for (block in mainBlock - 1 downTo 0) {
            val indexes = stats.blockIndexes[block]
            if (indexes.isEmpty()) break  // 不可判定块中断延伸，之前块保持保守
            if (indexes.last() == expectedBefore) {
                expectedBefore = indexes.first() - 1
            } else {
                candidate[block] = true
            }
        }

        segments.forEachIndexed { index, segment ->
            val block = segment.blockIndex
            if (block != mainBlock && candidate[block] && stats.blockTotal[block] <= AD_BLOCK_MAX_TOTAL_S) {
                flags[index] = true
            }
        }
        return flags
    }

    /** 提取切片文件名尾部数字序号（如 8dac599d7cc000000.ts → 0、seg01.ts → 1），无数字返回 null */
    private fun segmentIndex(uri: String): Long? {
        val name = uri.substringBefore('?').substringAfterLast('/')
        val match = Regex("(\\d+)\\.[^.]+$").find(name) ?: Regex("(\\d+)$").find(name) ?: return null
        return match.groupValues[1].toLongOrNull()
    }
}
