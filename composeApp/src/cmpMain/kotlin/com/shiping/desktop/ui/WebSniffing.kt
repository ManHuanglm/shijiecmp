package com.shiping.desktop.ui

/** 嗅探结果：真实媒体直链 + 沿用的播放请求头 */
data class SniffedMedia(
    val url: String,
    val headers: Map<String, String>,
)

/** 当前平台是否支持 WebView 网页嗅探（parse=1 网页解析播放） */
expect val supportsWebSniffing: Boolean

/**
 * WebView 嗅探网页解析页背后的真实媒体地址（parse=1 场景）：
 * 加载 [parseUrl] 监听媒体请求，未命中且 [fallbackUrl] 非空时回退嗅探原始网页
 * （解析接口失效时源页常自带播放器，直接嗅探即可命中）。
 * 命中返回直链与沿用请求头；超时/失败/平台不支持返回 null。
 */
expect suspend fun sniffParsePlayUrl(parseUrl: String, fallbackUrl: String?, headers: Map<String, String>): SniffedMedia?
