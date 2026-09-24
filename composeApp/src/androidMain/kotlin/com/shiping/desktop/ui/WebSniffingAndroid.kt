package com.shiping.desktop.ui

import com.shiping.app.ShipingApp
import com.shiping.app.data.player.WebSniffer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Android 支持网页嗅探：WebView 承载 parse=1 解析页，监听媒体请求命中直链 */
actual val supportsWebSniffing: Boolean = true

/** WebView 嗅探实现：解析页失败时回退嗅探原始网页，回调式转协程挂起 */
actual suspend fun sniffParsePlayUrl(
    parseUrl: String,
    fallbackUrl: String?,
    headers: Map<String, String>,
): SniffedMedia? = suspendCancellableCoroutine { continuation ->
    val sniffer = WebSniffer()
    continuation.invokeOnCancellation { sniffer.cancel() }
    val onSniffed: (String?) -> Unit = { url ->
        if (continuation.isActive) continuation.resume(url?.let { SniffedMedia(it, headers) })
    }
    sniffer.sniff(ShipingApp.context, parseUrl) { sniffedUrl ->
        // 解析页嗅探失败时回退直接嗅探原始网页（解析接口失效时源页常自带播放器）
        if (sniffedUrl != null || fallbackUrl.isNullOrBlank()) {
            onSniffed(sniffedUrl)
        } else {
            sniffer.sniff(ShipingApp.context, fallbackUrl, onResult = onSniffed)
        }
    }
}
