package com.shiping.app.data.spider

import com.whl.quickjs.android.QuickJSLoader
import java.io.File

/**
 * JS 爬虫加载器（Android 端）：初始化 quickjs native（aar 内置各 ABI so），
 * 创建 com.fongmi.quickjs.crawler.Spider 实例（api 为 drpy2/独立 js 地址）。
 */
object JsSpiderLoader {

    @Volatile
    private var initialized = false

    fun create(api: String, siteKey: String): com.fongmi.quickjs.crawler.Spider {
        if (!initialized) {
            runCatching { QuickJSLoader.init() }
            initialized = true
        }
        return com.fongmi.quickjs.crawler.Spider(api, null).also { it.siteKey = siteKey }
    }
}
