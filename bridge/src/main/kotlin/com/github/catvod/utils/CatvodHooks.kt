package com.github.catvod.utils

/**
 * 爬虫桥接层宿主注入钩子：桥接层（:bridge）不得反向依赖宿主（:shared），
 * 宿主启动时注入实现。全部钩子有安全默认值，未注入时桥接行为不变。
 */
object CatvodHooks {

    /** 返回「host:port」格式 HTTP 代理配置，null/空串表示不启用 */
    @JvmStatic
    @Volatile
    var httpProxyResolver: () -> String? = { null }
}
