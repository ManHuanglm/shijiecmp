package com.shiping.app.data.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.shiping.app.util.AppLog
import com.shiping.app.util.Constants
import java.io.ByteArrayOutputStream

/**
 * m3u8 播放列表去广告过滤器（迁移自源项目 shiping 安卓端，关键词 + 时长分块 + 目录指纹组合）。
 *
 * 识别规则：
 * 1. 关键词：切片 URL 命中 [Constants.AD_URL_KEYWORDS] 黑名单 → 广告
 * 2. 时长分块启发式：按 #EXT-X-DISCONTINUITY 将切片分块，与主内容块（总时长最长）相比，
 *    平均切片时长偏差大（比值 < [Constants.AD_DURATION_RATIO_MIN] 或 > [Constants.AD_DURATION_RATIO_MAX]）
 *    且块总时长 <= [Constants.AD_BLOCK_MAX_TOTAL_S] 的块 → 广告
 * 3. 目录指纹：非主块且块总时长 <= [Constants.AD_BLOCK_MAX_TOTAL_S]，
 *    其切片目录与主块主目录不同 → 广告
 *    （modujx 系典型：正片 /20260710/xxx/2000kb/，广告 /20260831/xxx/10092kb/，
 *    即使广告切片时长与正片接近也能命中）
 *
 * 安全策略：
 * - 主播放列表（#EXT-X-STREAM-INF，多清晰度）不过滤，避免误删清晰度条目
 * - 广告块内的 #EXT-X-KEY/#EXT-X-MAP 标签随切片一并移除，
 *   避免残留 METHOD=NONE 导致后续加密正片按明文解析而花屏
 * - 若判定结果会删掉全部切片，视为误判，放弃过滤返回原文
 */
object HlsAdFilter {

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
        val blockAd = computeBlockAdFlags(segments)

        val removedLines = HashSet<Int>()
        var removedCount = 0
        segments.forEachIndexed { index, segment ->
            if (keywordAd[index] || blockAd[index]) {
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
        AppLog.d(TAG, "播放列表过滤：移除 $removedCount/${segments.size} 个疑似广告切片")
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
        return Constants.AD_URL_KEYWORDS.any { lower.contains(it) }
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

    /** 规则二/三：分块启发式（时长偏差 + 目录指纹），返回每个切片是否属于广告块 */
    private fun computeBlockAdFlags(segments: List<Segment>): BooleanArray {
        val flags = BooleanArray(segments.size)
        val blockCount = segments.maxOf { it.blockIndex } + 1
        // 无分块或只有一个块时，没有对照主块，启发式不生效
        if (blockCount < 2) return flags

        val blockTotal = FloatArray(blockCount)
        val blockSum = FloatArray(blockCount)
        val blockSegCount = IntArray(blockCount)
        val blockDirCount = Array(blockCount) { HashMap<String, Int>() }
        for (segment in segments) {
            val block = segment.blockIndex
            blockTotal[block] += segment.durationSec
            blockSum[block] += segment.durationSec
            blockSegCount[block]++
            blockDirCount[block].merge(segment.dir, 1, Int::plus)
        }
        // 主内容块：总时长最长的块
        val mainBlock = blockTotal.indices.maxBy { blockTotal[it] }
        val mainAvg = blockSum[mainBlock] / blockSegCount[mainBlock].coerceAtLeast(1)
        // 各块的主目录（出现最多的目录指纹）
        val blockDominantDir = Array(blockCount) { block ->
            blockDirCount[block].maxByOrNull { it.value }?.key.orEmpty()
        }
        val mainDir = blockDominantDir[mainBlock]

        segments.forEachIndexed { index, segment ->
            val block = segment.blockIndex
            if (block == mainBlock) return@forEachIndexed
            val durationAd = mainAvg > 0f && run {
                val ratio = blockSum[block] / blockSegCount[block].coerceAtLeast(1) / mainAvg
                ratio < Constants.AD_DURATION_RATIO_MIN || ratio > Constants.AD_DURATION_RATIO_MAX
            }
            // 目录指纹：广告切片常与正片位于不同目录（不同日期/码率目录）
            val foreignDirAd = blockDominantDir[block] != mainDir
            if (blockTotal[block] <= Constants.AD_BLOCK_MAX_TOTAL_S &&
                (durationAd || foreignDirAd)
            ) {
                flags[index] = true
            }
        }
        return flags
    }

    private const val TAG = "HlsAdFilter"
}

/**
 * 拦截 m3u8 播放列表请求并重写响应体的 DataSource（迁移自源项目方案）。
 * 播放列表通过包装的数据源整体读入 → [HlsAdFilter] 过滤 → 以新长度返回；
 * 切片请求原样透传（切片 URL 未变，相对路径仍按原始播放列表地址解析）。
 */
@UnstableApi
class AdFilterDataSource(private val upstream: DataSource) : DataSource {

    private var delegate: DataSource? = null

    /** 非空表示当前响应是已重写的播放列表 */
    private var playlistBytes: ByteArray? = null
    private var readPosition = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val isPlaylist = dataSpec.uri.path
            ?.substringBefore('?')
            ?.endsWith(".m3u8") == true

        delegate = upstream
        if (!isPlaylist) {
            return upstream.open(dataSpec)
        }

        // 整体读入播放列表原文
        val length = upstream.open(dataSpec)
        val out = ByteArrayOutputStream(if (length > 0) length.toInt() else 8 * 1024)
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = upstream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }

        val result = HlsAdFilter.filter(out.toString("UTF-8"))
        val bytes = result.text.toByteArray(Charsets.UTF_8)
        playlistBytes = bytes
        readPosition = 0
        return bytes.size.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val bytes = playlistBytes
        if (bytes != null) {
            if (readPosition >= bytes.size) return C.RESULT_END_OF_INPUT
            val size = minOf(length, bytes.size - readPosition)
            System.arraycopy(bytes, readPosition, target, offset, size)
            readPosition += size
            return size
        }
        return delegate?.read(target, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            playlistBytes = null
            readPosition = 0
        }
    }
}

/** [AdFilterDataSource] 的工厂，包装现有数据源工厂（如 HTTP 数据源） */
@UnstableApi
class AdFilterDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        AdFilterDataSource(upstreamFactory.createDataSource())
}
