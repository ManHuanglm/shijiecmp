package com.shiping.shared.spider

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 本地代理服务（桥接版，对齐 FongMi Server 与原两端 SpiderProxyServer）：
 * 爬虫源返回的播放地址常为 http://127.0.0.1:<port>/proxy?do=m3u8&url=...（m3u8 切片重写、
 * 解密、弹幕转换等），由爬虫 jar 的 proxy(Map) 方法处理。宿主需启动 NanoHTTPD 监听并
 * 将端口注入 com.github.catvod.Proxy（经 SpiderProxyBridge.onPortChanged），否则地址中
 * 端口为 -1 无法播放。同时提供 /action?do=push 局域网推送接收（iOS/Web 中继亦复用此协议）。
 *
 * 平台差异经 [SpiderProxyBridge] 注入：先 install 再 start。
 */
object SpiderProxyServer {

    private const val TAG = "SpiderProxyServer"

    /** 代理端口探测范围（对齐 FongMi：9978 起逐个尝试） */
    private const val PROXY_PORT_START = 9978

    private const val PROXY_PORT_END = 9999

    /** /action 推送指令值（对齐 FongMi Action.do=push） */
    private const val ACTION_DO_PUSH = "push"

    /** 推送成功响应文本（对齐 FongMi Nano.ok） */
    private const val ACTION_OK_TEXT = "ok"

    @Volatile
    private var bridge: SpiderProxyBridge? = null

    @Volatile
    private var server: ProxyHttpServer? = null

    /** 注入平台桥（幂等，启动前调用） */
    fun install(bridge: SpiderProxyBridge) {
        this.bridge = bridge
    }

    /** 当前监听端口（未启动时 -1），供设置页展示局域网推送地址 */
    val listeningPort: Int
        get() = server?.listeningPort ?: -1

    /** 推送事件流（播放层订阅：收到推送自动打开播放器；iOS/Web 中继客户端消费同一协议） */
    private val _pushEvents = MutableSharedFlow<PushMessage>(extraBufferCapacity = 16)
    val pushEvents = _pushEvents.asSharedFlow()

    /** 代理请求计数（设置页诊断展示用） */
    private val _proxyRequestCount = MutableStateFlow(0L)
    val proxyRequestCount = _proxyRequestCount.asStateFlow()

    /** 局域网推送地址（http://<非回环 IPv4>:<端口>），未启动或无可用网卡时为 null */
    fun pushAddress(): String? {
        val port = listeningPort
        if (port <= 0) return null
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: return null
        return "http://$ip:$port"
    }

    /** 启动本地代理（幂等）：从 9978 起探测空闲端口（对齐 FongMi Server，生态内爬虫硬编码 9978） */
    fun start() {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            for (port in PROXY_PORT_START until PROXY_PORT_END) {
                val httpServer = ProxyHttpServer(port)
                try {
                    httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    bridge?.onPortChanged?.invoke(port)
                    server = httpServer
                    log('i', TAG, "本地代理已启动 port=$port")
                    return
                } catch (e: Exception) {
                    log('w', TAG, "端口 $port 被占用: ${e.message}${occupyingProcessHint(port)}")
                }
            }
            log('e', TAG, "本地代理启动失败：$PROXY_PORT_START-$PROXY_PORT_END 端口均不可用")
        }
    }

    private fun log(level: Char, tag: String, message: String) {
        bridge?.logger?.invoke(level, tag, message)
    }

    /**
     * 端口占用进程提示（Windows）：netstat 找到 LISTENING 该端口的 PID，
     * 再经 tasklist 取进程名，日志直接给出占用者，避免"双开应用"这类困惑。
     */
    private fun occupyingProcessHint(port: Int): String {
        if (!System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) return ""
        return runCatching {
            val netstat = ProcessBuilder("netstat", "-ano", "-p", "tcp").start().inputStream.bufferedReader().readText()
            val listenLine = netstat.lineSequence()
                .firstOrNull { it.contains(":$port ") && it.contains("LISTENING", ignoreCase = true) } ?: return ""
            val pid = listenLine.trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.all(Char::isDigit) } ?: return ""
            val name = runCatching {
                ProcessBuilder("tasklist", "/fi", "PID eq $pid", "/fo", "csv", "/nh")
                    .start().inputStream.bufferedReader().readText()
                    .lineSequence().firstOrNull()?.split("\",\"")?.getOrNull(1)?.trim('"')
            }.getOrNull()
            "（占用进程 PID=$pid name=${name ?: "未知"}）"
        }.getOrDefault("")
    }

    // 绑定所有网卡（NanoHTTPD(port) 默认 0.0.0.0）：局域网内其他设备（手机推送）可访问
    private class ProxyHttpServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            _proxyRequestCount.value++
            val uri = session.uri ?: return textResponse(Status.INTERNAL_ERROR, "空路径")
            if (uri.startsWith("/action")) return handleAction(session)
            if (uri.startsWith("/proxy")) return handleProxy(session)
            val extra = bridge?.extraRouteHandler?.invoke(session)
            return extra ?: textResponse(Status.NOT_FOUND, "未知路径: $uri")
        }

        /** 对齐 FongMi：query 参数 + 请求头合并后交给爬虫 proxy 方法处理 */
        private fun handleProxy(session: IHTTPSession): Response {
            val params = HashMap<String, String>()
            params.putAll(session.parms)
            params.putAll(session.headers)
            return try {
                when (val result = bridge?.dispatchProxy?.invoke(params)) {
                    null -> {
                        log('i', "Proxy", "代理无处理实例 do=${params["do"]} url=${params["url"]}")
                        textResponse(Status.INTERNAL_ERROR, "无爬虫处理该代理请求")
                    }
                    else -> buildResponse(result)
                }
            } catch (t: Throwable) {
                log('e', "Proxy", "代理请求处理失败 do=${params["do"]}: ${t.message}")
                textResponse(Status.INTERNAL_ERROR, t.message ?: t.toString())
            }
        }

        /** Object[] {code, mimeType, InputStream, headers?} → 流式响应 */
        private fun buildResponse(result: Array<Any>): Response {
            if (result.size < 3 || result[2] !is InputStream) {
                return textResponse(Status.INTERNAL_ERROR, "无效的代理响应")
            }
            val status = Status.lookup((result[0] as Int).coerceIn(100, 599)) ?: Status.INTERNAL_ERROR
            val mime = result[1] as String
            val stream = result[2] as InputStream
            val response = newChunkedResponse(status, mime, stream)
            if (result.size > 3 && result[3] is Map<*, *>) {
                for (entry in (result[3] as Map<*, *>)) {
                    response.addHeader(entry.key.toString(), entry.value.toString())
                }
            }
            return response
        }

        /**
         * 手机推送协议（对齐 FongMi /action）：GET query 或 POST form 均可。
         * POST 时先 parseBody 将 form 参数并入 session.parms（参考 FongMi Nano.parse）。
         */
        private fun handleAction(session: IHTTPSession): Response {
            if (session.method == NanoHTTPD.Method.POST) {
                runCatching { session.parseBody(HashMap()) }
                    .onFailure { log('w', TAG, "action POST 解析失败: ${it.message}") }
            }
            return when (val action = session.parms["do"]) {
                ACTION_DO_PUSH -> handlePush(session.parms)
                else -> textResponse(Status.NOT_IMPLEMENTED, "不支持的 action: $action")
            }
        }

        /** do=push：提取 url（必需）与 title（可选），投递到推送事件流 */
        private fun handlePush(params: Map<String, String>): Response {
            val url = params["url"].orEmpty()
            if (url.isBlank()) return textResponse(Status.BAD_REQUEST, "缺少 url 参数")
            val emitted = _pushEvents.tryEmit(PushMessage(url, params["title"].orEmpty()))
            if (!emitted) log('w', TAG, "推送事件投递失败（缓冲已满）: $url")
            return textResponse(Status.OK, ACTION_OK_TEXT)
        }

        private fun textResponse(status: Status, message: String): Response =
            newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message.ifBlank { status.description })
    }
}
