package com.shiping.desktop.player

import com.shiping.desktop.util.Constants
import java.net.URLEncoder

/**
 * 播放地址解析（SpiderPlayInfo -> 内核可直接播放的地址）：
 * - 无 header 或仅 UA/Referer：原样直链（内核属性覆盖）
 * - 含 Cookie 等其他 header：包装为本地代理 /hdr 地址流式转发
 */
object PlaybackUrlResolver {

    /** 需要 /hdr 代理转发的 header 键（UA/Referer 之外的都算） */
    fun needsHdrProxy(headers: Map<String, String>): Boolean = headers.keys.any { key ->
        !key.equals("User-Agent", true) && !key.equals("Referer", true)
    }

    /** 包装为 /hdr 代理地址（port 为本地代理端口） */
    fun wrapHdrProxy(url: String, headers: Map<String, String>, port: Int): String {
        val headerText = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
        val encodedUrl = urlEncode(url)
        val encodedHeaders = urlEncode(headerText)
        return "http://127.0.0.1:$port/hdr?url=$encodedUrl&h=$encodedHeaders"
    }

    /** 生成完整播放地址：按需包装 /hdr（headers 含 UA/Referer 之外字段时） */
    fun resolve(playUrl: String, headers: Map<String, String>, proxyPort: Int): String =
        if (needsHdrProxy(headers)) wrapHdrProxy(playUrl, headers, proxyPort) else playUrl

    /**
     * URL 编码：URLEncoder 的空格转义为 +，NanoHTTPD 不解 + 为空格，
     * 统一替换为 %20 保证两端一致。
     */
    private fun urlEncode(raw: String): String =
        URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")
}

/** 倍速展示格式（0.5 -> 0.5x，1.0 -> 1.0x） */
fun formatSpeed(rate: Float): String = "${"%.1f".format(rate)}x"

/** 时长/进度格式化（mm:ss 或 h:mm:ss） */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** 判断是否为直链（m3u8/mp4/flv/ts），parse=1 的网页地址需要解析不支持 */
fun isDirectStreamUrl(url: String): Boolean {
    val path = url.substringBefore("?").lowercase()
    return Constants.DIRECT_STREAM_SUFFIXES.any { path.endsWith(it) }
}
