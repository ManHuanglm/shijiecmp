package com.shiping.app

import android.app.Application
import android.content.Context
import com.shiping.app.data.spider.SpiderRuntime
import com.shiping.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShipingApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        AppLog.init(this)
        // cmpMain 统一日志出口装配：UI 层 VmLog → AppLog（logcat + 文件）
        com.shiping.desktop.ui.VmLog.sink = { level, tag, message ->
            when (level) {
                'w' -> AppLog.w(tag, message)
                'e' -> AppLog.e(tag, message)
                else -> AppLog.i(tag, message)
            }
        }
        // 爬虫桥接：jar 爬虫会调用 Init.set(context) 注入的宿主上下文（FongMi catvod 规范），
        // 必须在首个爬虫实例化前完成初始化，否则爬虫内取上下文为 null 导致失败。
        // catvod Java 桥经反射调用（KMP android target 的 kotlinc 不解析 Java 源）
        runCatching {
            Class.forName("com.github.catvod.Init")
                .getMethod("set", Context::class.java)
                .invoke(null, this)
        }
        // 爬虫运行时：安装本地代理桥并启动代理服务（9978 起探测）
        SpiderRuntime.init(this)
        // 依赖容器：DataStore 偏好 + Room 仓库栈 + media3 内核
        ShipingGraph.init(this)
        // 启动预热：默认 API 源/直播源插入 + 各仓库刷新（解析源无默认项，对齐桌面启动行为）
        appScope.launch { runCatching { ShipingGraph.warmUp() } }
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
