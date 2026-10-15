package com.shiping.desktop.data.spider

import java.io.File

/**
 * JS 爬虫加载器（桌面端）：加载 libquickjs native 后创建
 * com.fongmi.quickjs.crawler.Spider 实例（api 为 drpy2/独立 js 地址）。
 * native 探测顺序：compose 应用资源目录（镜像 app\resources 的上级即 exe 目录）、
 * 工作目录、java.library.path（System.loadLibrary 兜底）——dll 由 mingw 从
 * Harlon quickjs-wrapper 源码编译（quickjs.dll，与本类同名库绑定）。
 */
object JsSpiderLoader {

    @Volatile
    private var initialized = false

    fun create(api: String, siteKey: String): com.fongmi.quickjs.crawler.Spider {
        if (!initialized) {
            runCatching { System.loadLibrary("quickjs") }.onFailure { loadError ->
                val candidates = buildList {
                    System.getProperty("compose.application.resources.dir")?.let { res ->
                        File(res).parentFile?.parentFile?.let { add(File(it, "quickjs.dll")) }
                        add(File(res, "quickjs.dll"))
                    }
                    add(File("quickjs.dll").absoluteFile)
                }
                val dll = candidates.firstOrNull { it.exists() }
                if (dll == null) throw loadError
                System.load(dll.absolutePath)
            }
            initialized = true
        }
        return com.fongmi.quickjs.crawler.Spider(api, null).also { it.siteKey = siteKey }
    }
}
