package com.shiping.desktop.data.spider

/**
 * 本地代理端口状态（jvmCommon 内聚，替代对 com.github.catvod.Proxy 的直接依赖）：
 * - 桌面端 SpiderProxyBridge.onPortChanged 与 Android 端桥回调（M4）各自写入；
 * - SpiderMapper 改写爬虫 jar 拼出的 127.0.0.1:-1 占位地址时读取。
 * Android 端同时在桥回调里调用自家 catvod Proxy.set（composeApp/androidMain）。
 */
object SpiderProxyPort {

    /** 当前本地代理端口（未启动时 -1） */
    @Volatile
    var current: Int = -1
}
