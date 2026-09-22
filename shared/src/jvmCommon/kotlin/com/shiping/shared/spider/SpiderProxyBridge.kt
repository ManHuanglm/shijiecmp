package com.shiping.shared.spider

import fi.iki.elonen.NanoHTTPD

/** 局域网推送消息（对齐 FongMi /action?do=push 协议） */
data class PushMessage(val url: String, val title: String = "")

/**
 * 本地代理平台桥：SpiderProxyServer（shared）不直接依赖各端爬虫运行时与日志实现，
 * 由各端在启动时注入。桌面端桥接 DesktopSpiderRuntime + /hdr 路由 + AppLog；
 * Android 端（M4）桥接 SpiderRuntime + 自有日志。
 *
 * @param onPortChanged    代理端口确定后回调（两端各自注入 com.github.catvod.Proxy.set）
 * @param dispatchProxy    /proxy 请求分发到本端爬虫运行时（对齐 FongMi JarLoader.proxy）
 * @param extraRouteHandler 本端扩展路由（桌面 /hdr 直链 header 注入），返回 null 走 404
 * @param logger           日志回调（level: 'i'/'w'/'e'），解耦各端日志实现
 */
class SpiderProxyBridge(
    val onPortChanged: (Int) -> Unit,
    val dispatchProxy: (Map<String, String>) -> Array<Any>?,
    val extraRouteHandler: ((NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response?)? = null,
    val logger: (level: Char, tag: String, message: String) -> Unit = { _, _, _ -> },
)
