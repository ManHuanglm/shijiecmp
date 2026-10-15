package com.shiping.shared.spider

/**
 * 爬虫引擎统一接口（commonMain，JSON 字符串进出）：
 * 两端实现共用同一签名——Android 为 DexClassLoader 直载（SpiderRuntime），
 * 桌面为 dex2jar+URLClassLoader+unidbg 加固壳链路（DesktopSpiderRuntime）。
 * 平台初始化（Context/数据目录）不进接口，由各端 object 自身的 init 承担。
 */
interface SpiderEngine {

    /** 首页：分类 + 推荐列表（TVBox 标准两参签名，主流 jar 重写两参版本） */
    suspend fun homeContent(jarUrl: String, key: String, ext: String = "", siteKey: String = ""): String

    /** 首页推荐（部分 jar 只实现 homeVideoContent） */
    suspend fun homeVideoContent(jarUrl: String, key: String, ext: String = "", siteKey: String = ""): String

    /** 分类列表 */
    suspend fun categoryContent(
        jarUrl: String,
        key: String,
        tid: String,
        pg: String,
        extend: Map<String, String> = emptyMap(),
        ext: String = "",
        siteKey: String = "",
    ): String

    /** 详情 */
    suspend fun detailContent(jarUrl: String, key: String, id: String, ext: String = "", siteKey: String = ""): String

    /** 搜索（TVBox 标准签名为 int pg） */
    suspend fun searchContent(
        jarUrl: String,
        key: String,
        keyword: String,
        pg: Int,
        ext: String = "",
        siteKey: String = "",
    ): String

    /** 播放信息 */
    suspend fun playerContent(
        jarUrl: String,
        key: String,
        flag: String,
        id: String,
        ext: String = "",
        siteKey: String = "",
    ): String

    /**
     * 本地代理请求分发（/proxy 端点）：返回 Object[] {code, mimeType, InputStream, headers?}
     * 或 null（无爬虫处理该请求）。
     */
    fun proxyInvoke(params: Map<String, String>): Array<Any>?

    /**
     * action 协议：TVBox 生态约定，分类列表项携带 "action" 字段时点击应调爬虫 action
     * （如网盘源的「登入自己网盘 / 清除xxCookie」），返回提示文本（可能为空）。
     */
    suspend fun actionContent(jarUrl: String, key: String, ext: String = "", siteKey: String = "", action: String): String = ""
}
