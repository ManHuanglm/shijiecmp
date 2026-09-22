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
        // 初始化默认 API 源和电视直播源（解析源无默认项，对齐桌面启动行为）
        appScope.launch {
            runCatching { ShipingGraph.apiSourceRepository.initDefaultSource() }
            runCatching { ShipingGraph.tvRepository.initDefaultSource() }
        }
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
