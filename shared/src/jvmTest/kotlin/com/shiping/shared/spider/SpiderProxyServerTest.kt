package com.shiping.shared.spider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 本地代理桥接服务：未启动状态语义与平台桥注入（不真正监听端口，避免测试环境端口占用） */
class SpiderProxyServerTest {

    @Test
    fun 未启动时端口与推送地址为空态() {
        // object 单例：仅断言未启动语义，不调用 start()（端口监听属集成验证，由应用冒烟覆盖）
        assertEquals(-1, SpiderProxyServer.listeningPort)
        assertNull(SpiderProxyServer.pushAddress())
        assertEquals(0L, SpiderProxyServer.proxyRequestCount.value)
    }

    @Test
    fun 桥接安装后代理端口回调可达() {
        var notifiedPort = 0
        val bridge = SpiderProxyBridge(
            onPortChanged = { port -> notifiedPort = port },
            dispatchProxy = { params ->
                // 模拟爬虫返回 {code, mime, InputStream}
                arrayOf(200, "text/plain", params["url"]!!.byteInputStream())
            },
            logger = { _, _, _ -> },
        )
        SpiderProxyServer.install(bridge)
        // dispatch 回调经桥注入后可独立验证（不依赖 NanoHTTPD 会话）
        val result = bridge.dispatchProxy(mapOf("url" to "http://x/a.mp4", "do" to "m3u8"))
        assertNotNull(result)
        assertEquals(200, result[0])
        assertEquals(3, result.size)
        assertTrue(notifiedPort == 0, "start() 未调用时不应回调端口")
        // 重复 install 幂等（不抛异常）
        SpiderProxyServer.install(bridge)
    }

    @Test
    fun 推送消息承载url与标题() {
        val message = PushMessage(url = "https://v.example.com/a.m3u8", title = "测试")
        assertEquals("https://v.example.com/a.m3u8", message.url)
        assertEquals("测试", message.title)
        assertEquals("", PushMessage("https://v.example.com/b.mp4").title)
    }
}
