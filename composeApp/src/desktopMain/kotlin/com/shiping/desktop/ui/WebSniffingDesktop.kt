package com.shiping.desktop.ui

/** 桌面无 WebView：不支持网页嗅探（parse=1 场景由调用方提示更换线路） */
actual val supportsWebSniffing: Boolean = false

/** 桌面端无嗅探实现，恒返回 null */
actual suspend fun sniffParsePlayUrl(
    parseUrl: String,
    fallbackUrl: String?,
    headers: Map<String, String>,
): SniffedMedia? = null
