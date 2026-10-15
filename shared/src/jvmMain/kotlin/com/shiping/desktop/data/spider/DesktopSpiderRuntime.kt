package com.shiping.desktop.data.spider

import android.content.Context
import com.shiping.desktop.data.repository.ClanPaths
import com.shiping.desktop.spider.DesktopProxyRoutes
import com.shiping.desktop.util.AppLog
import com.shiping.shared.spider.SpiderEngine
import com.shiping.shared.spider.SpiderProxyBridge
import com.shiping.shared.spider.SpiderProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 桌面版 TVBox 爬虫源运行时（对齐 app SpiderRuntime 语义，加载方式桌面化）：
 * - 按站点 jar 地址下载 dex jar 到 <data>/files/spider/<md5>.jar，md5 校验 + 坏缓存自愈
 * - dex2jar 转换为 JVM 可加载 jar（按 dex jar 内容 md5 缓存，源方更新自动重转换）
 * - URLClassLoader（parent-first）加载，桥接类 com.github.catvod.* 由宿主类路径提供
 * - 反射调用 Spider 子类的 homeContent/detailContent 等方法（JSON 字符串进出）
 * - 加固壳 jar（饭太硬：assets 内 guard 资源 + ftyguard so）走专用链路：guard 解包出
 *   payload dex 后与壳 jar 各自转换双产物，unidbg 引擎（FtyGuardEngine）桥接解密
 * - 转换/补丁产物先写临时文件再原子替换：多实例共享数据目录时不会读到半成品或未补丁 payload
 */
object DesktopSpiderRuntime : SpiderEngine {

    private const val TAG = "SpiderRuntime"

    /** dex jar 缓存目录名（files 下） */
    private const val JAR_DIR = "spider"

    /** 转换产物缓存目录名（cache 下） */
    private const val CONVERTED_DIR = "spider_converted"

    /** 爬虫站点 key 前缀（如 csp_XXX） */
    private const val CSP_KEY_PREFIX = "csp_"

    /** Spider 子类所在包名前缀（老 jar：CatVodOpen 约定） */
    private const val SPIDER_CLASS_PACKAGE = "com.github.catvod.crawler.Spider"

    /** Spider 子类所在包名前缀（主流 jar：FongMi 约定，如 csp_PanSearch -> spider.PanSearch） */
    private const val SPIDER_PACKAGE_MODERN = "com.github.catvod.spider."

    /** 每个 jar 候选地址的下载尝试次数（偶发网络失败/错误页自愈） */
    private const val DOWNLOAD_ATTEMPTS = 2

    /** 加固壳特征：guard 资源条目后缀（内含 payload classes.dex 的 ZIP 容器） */
    private const val GUARD_ASSET_SUFFIX = ".guard"

    /** 加固壳特征：native 库条目名关键字（lib 下 ftyguard_v8.so） */
    private const val GUARD_SO_KEYWORD = "ftyguard"

    /** 加固壳转换产物后缀：壳 jar 转换产物（payload 依赖的壳桥接类） */
    private const val GUARD_SHELL_SUFFIX = "_shell.jar"

    /** 加固壳转换产物后缀：guard 内 payload dex 的转换产物（爬虫类所在） */
    private const val GUARD_PAYLOAD_SUFFIX = "_payload.jar"

    /** guard 条目解包缓存目录名（cache 下） */
    private const val GUARD_EXTRACT_DIR = "spider_guard"

    /** 加固壳 unidbg 引擎工作目录名（cache 下） */
    private const val GUARD_ENGINE_DIR = "fty_guard_engine"

    /** 加固壳站点 key 后缀（csp_AppSxGuard -> 剥离后定位 payload 类 AppSx） */
    private const val GUARD_SITE_SUFFIX = "Guard"

    /** 网盘推送协议参数名（/proxy?do=<drive>&type=input&推送=<值>，爬虫生态约定） */
    private const val PAN_INPUT_PARAM = "推送"

    /** 爬虫类缺失失败缓存时长：重下验证后仍缺类的 key 在此期间快速失败，不再逐请求重下 */
    private const val MISSING_SITE_RETRY_MILLIS = 10 * 60 * 1000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var dataDir: File? = null

    /** dex jar 本地路径缓存：jarUrl -> 文件绝对路径 */
    private val jarFiles = ConcurrentHashMap<String, String>()

    /** 类加载器缓存：转换产物路径 -> URLClassLoader */
    private val classLoaders = ConcurrentHashMap<String, URLClassLoader>()

    /**
     * 加固壳产物关联：payload 产物路径 -> shell/source/guard 三件套。
     * shellJar：壳 jar 转换产物（payload 引用的壳桥接类）；sourceJar：原始壳 dex jar
     * （unidbg 引擎提取 so/guard 资产）；guardFile：解包出的 guard 容器（payload dex 来源）。
     */
    private class GuardArtifacts(val shellJar: String, val sourceJar: String, val guardFile: String)

    private val guardArtifacts = ConcurrentHashMap<String, GuardArtifacts>()

    /** Spider 实例缓存：jarUrl|key|ext|siteKey -> 实例 */
    private val spiders = ConcurrentHashMap<String, Any>()

    /** 爬虫类缺失失败缓存：jarUrl|key -> 失效时间戳（重下转换后仍缺类才记录） */
    private val missingSpiders = ConcurrentHashMap<String, Long>()

    /** 最近使用的转换产物路径（本地代理请求优先分发给该 jar 的静态 Proxy 类） */
    @Volatile
    private var recentLoaderPath: String? = null

    /** 注入宿主上下文与数据目录（AppContainer 初始化时调用），同时安装代理桥并启动本地代理 */
    fun init(context: Context, dataDir: File) {
        // AES/ECB/PKCS7Padding 等算法需要 BouncyCastle（Android 内置支持，JVM 需显式注册）
        runCatching {
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
        appContext = context
        // catvod 桥上下文注入：Python 桥（Path.py 缓存）等依赖 Init.context() 的链路必需
        com.github.catvod.Init.set(context)
        DesktopSpiderRuntime.dataDir = dataDir
        SpiderProxyServer.install(
            SpiderProxyBridge(
                onPortChanged = { port ->
                    com.github.catvod.Proxy.set(port)
                    // 同步到 jvmCommon 端口状态：SpiderMapper 改写占位代理地址用
                    com.shiping.desktop.data.spider.SpiderProxyPort.current = port
                },
                dispatchProxy = { params -> proxyInvoke(params) },
                extraRouteHandler = { session -> DesktopProxyRoutes.handle(session) },
                logger = { level, tag, message ->
                    when (level) {
                        'w' -> AppLog.w(tag, message)
                        'e' -> AppLog.e(tag, message)
                        else -> AppLog.i(tag, message)
                    }
                },
            ),
        )
        SpiderProxyServer.start()
    }

    /**
     * 本地代理请求分发（对齐 FongMi JarLoader.proxy）：
     * 1. 优先用最近使用 jar 的 com.github.catvod.spider.Proxy.proxy(Map) 静态方法（生态约定）
     * 2. 加固壳 jar 的 ProxyOrigin.proxy(Map)（该生态混入库的真正代理实现，含 m3u8/图片/弹幕/cookie 分支）
     * 3. 其余已加载 jar 的同名静态类依次尝试
     * 4. 最后兜底遍历 Spider 实例的 proxy 实例方法
     * 返回 Object[] {code, mimeType, InputStream, headers?} 或 null（无爬虫处理）。
     */
    override fun proxyInvoke(params: Map<String, String>): Array<Any>? {
        recentLoaderPath?.let { path -> invokeJarProxy(path, params)?.let { return it } }
        for (path in classLoaders.keys) {
            if (path == recentLoaderPath) continue
            invokeJarProxy(path, params)?.let { return it }
        }
        for (spider in spiders.values) {
            try {
                val method = spider.javaClass.getMethod("proxy", Map::class.java)
                (method.invoke(spider, params) as? Array<Any>)?.let { return it }
            } catch (_: Exception) {
                // 该爬虫未实现 proxy 或调用失败，继续尝试下一个
            }
        }
        return null
    }

    /**
     * 单 jar 代理尝试：生态约定的 Proxy.proxy 优先，其后 ProxyOrigin.proxy（加固壳混入库）。
     * 结构性错误（LinkageError：类被遮蔽毒化/桥方法缺失等）自动重建 classloader 重试一次自愈：
     * 盘上产物多在首次加载后才补齐（多实例竞态）或缓存已损坏，按最新产物重载即可恢复，
     * 避免长运行实例因 JVM 已缓存的坏类整实例报废；重试仍失败才跳过该 jar。
     */
    private fun invokeJarProxy(jarPath: String, params: Map<String, String>): Array<Any>? {
        return try {
            invokeJarProxyDirect(jarPath, params)
        } catch (structural: LinkageError) {
            AppLog.w(
                TAG,
                "代理分发结构性错误，重建 classloader 重试: " +
                    "${structural.javaClass.simpleName}: ${structural.message}",
            )
            val context = appContext ?: return null
            runCatching { classLoaders.remove(jarPath)?.close() }
            runCatching { getLoader(context, jarPath) }
            try {
                invokeJarProxyDirect(jarPath, params)
            } catch (retry: LinkageError) {
                AppLog.w(
                    TAG,
                    "classloader 重建后代理仍失败，跳过该 jar: " +
                        "${retry.javaClass.simpleName}: ${retry.message}",
                )
                null
            }
        }
    }

    /** 单 jar 代理直调（不含自愈逻辑）：Proxy.proxy 优先，其后 ProxyOrigin.proxy */
    private fun invokeJarProxyDirect(jarPath: String, params: Map<String, String>): Array<Any>? {
        invokeStaticProxy(jarPath, params)?.let { return it }
        return invokeProxyOrigin(jarPath, params)
    }

    /**
     * 进程内推送网盘 Cookie/Token（对齐 /proxy?do=<drive>&type=input&推送=<值> 路由，
     * 与扫码推送页同链路；爬虫侧写入自身状态文件后走 token 交换）。
     * 需对应网盘 jar 已加载（进过一次网盘源）。
     * 判定：爬虫处理器的业务异常可能发生在状态文件写入之后（如夸克先写 ut 再验 401），
     * 故以 files/ 目录差分或处理器有响应为准，不单纯以是否抛异常定成败。
     */
    fun pushPanInput(drive: String, value: String): Boolean {
        val root = dataDir ?: return false
        val stateDir = File(root, "files")
        val before = stateDir.listFiles()?.associate { it.name to it.lastModified() } ?: emptyMap()
        val response = runCatching {
            proxyInvoke(mapOf("do" to drive, "type" to "input", PAN_INPUT_PARAM to value))
        }.getOrNull()
        val after = stateDir.listFiles()?.associate { it.name to it.lastModified() } ?: emptyMap()
        val persisted = after.any { (name, mtime) -> before[name] != mtime }
        return persisted || response != null
    }

    /** 调用 jar 内 com.github.catvod.spider.Proxy 的静态 proxy(Map) 方法 */
    private fun invokeStaticProxy(jarPath: String, params: Map<String, String>): Array<Any>? =
        runCatching {
            val loader = classLoaders[jarPath] ?: return null
            val clazz = loader.loadClass("com.github.catvod.spider.Proxy")
            val method = clazz.getMethod("proxy", Map::class.java)
            method.invoke(null, params) as? Array<Any>
        }.getOrElse { err -> resolveProxyFailure(err) }

    /**
     * 调用加固壳 payload 的 ProxyOrigin.proxy(Map)（com.github.catvod.spider.ProxyOrigin）：
     * 立播等壳 jar 的 Proxy 只是转发到壳 native 的空壳，真正的 m3u8 重写/弹幕/cookie
     * 处理在 payload 的 ProxyOrigin 里，桌面端必须直连它。
     */
    private fun invokeProxyOrigin(jarPath: String, params: Map<String, String>): Array<Any>? =
        runCatching {
            val loader = classLoaders[jarPath] ?: return null
            val clazz = loader.loadClass("com.github.catvod.spider.ProxyOrigin")
            val method = clazz.getMethod("proxy", Map::class.java)
            method.invoke(null, params) as? Array<Any>
        }.getOrElse { err -> resolveProxyFailure(err) }

    /**
     * 代理调用异常分流：方法体执行异常（InvocationTargetException 包装）原样上抛目标——
     * LinkageError 触发上层重建 classloader 自愈，业务异常（解析失败等）呈现给请求方；
     * 类加载期的结构性错误（类被毒化遮蔽等 NoClassDefFoundError 家族）同样上抛触发自愈；
     * 类/方法缺失属生态常态（jar 未实现该代理入口），返回 null 交由分发器尝试下一个处理器。
     */
    private fun resolveProxyFailure(err: Throwable): Array<Any>? {
        val target = (err as? InvocationTargetException)?.targetException ?: err
        if (err is InvocationTargetException) throw target
        if (target is LinkageError) throw target
        return null
    }

    // region 爬虫调用入口（IO 线程执行，失败抛出带原因的异常由上层呈现）

    /** 首页：分类 + 推荐列表（TVBox 标准两参签名，主流 jar 重写两参版本） */
    override suspend fun homeContent(jarUrl: String, key: String, ext: String, siteKey: String): String =
        invokeSafely(jarUrl, key, ext, siteKey) { spider ->
            invoke(spider, "homeContent", false, HashMap<String, String>())
        }

    /** 首页推荐（部分 jar 只实现 homeVideoContent） */
    override suspend fun homeVideoContent(jarUrl: String, key: String, ext: String, siteKey: String): String =
        invokeSafely(jarUrl, key, ext, siteKey) { spider ->
            invoke(spider, "homeVideoContent")
        }

    /** 分类列表 */
    override suspend fun categoryContent(
        jarUrl: String,
        key: String,
        tid: String,
        pg: String,
        extend: Map<String, String>,
        ext: String,
        siteKey: String,
    ): String = invokeSafely(jarUrl, key, ext, siteKey) { spider ->
        invoke(spider, "categoryContent", tid, pg, false, HashMap(extend))
    }

    /** 详情 */
    override suspend fun detailContent(jarUrl: String, key: String, id: String, ext: String, siteKey: String): String =
        invokeSafely(jarUrl, key, ext, siteKey) { spider ->
            invoke(spider, "detailContent", listOf(id))
        }

    /** action 协议（网盘源登入/清除 Cookie 等生态约定项），返回提示文本 */
    override suspend fun actionContent(
        jarUrl: String,
        key: String,
        ext: String,
        siteKey: String,
        action: String,
    ): String = invokeSafely(jarUrl, key, ext, siteKey) { spider ->
        invoke(spider, "action", action)
    }

    /**
     * 搜索（对齐 FongMi：第 1 页优先两参——生态大量源只实现两参 searchContent，
     * 三参是基类空桩直接返回空串，固定三参调用会让这些源静默无结果；
     * 两参返回空再回退三参，兼容只实现三参的源，翻页固定走三参）。
     */
    override suspend fun searchContent(
        jarUrl: String,
        key: String,
        keyword: String,
        pg: Int,
        ext: String,
        siteKey: String,
    ): String = invokeSafely(jarUrl, key, ext, siteKey) { spider ->
        if (pg > 1) {
            invoke(spider, "searchContent", keyword, false, pg)
        } else {
            invoke(spider, "searchContent", keyword, false).takeIf { it.isNotBlank() }
                ?: invoke(spider, "searchContent", keyword, false, pg)
        }
    }

    /** 播放信息 */
    override suspend fun playerContent(
        jarUrl: String,
        key: String,
        flag: String,
        id: String,
        ext: String,
        siteKey: String,
    ): String = invokeSafely(jarUrl, key, ext, siteKey) { spider ->
        invoke(spider, "playerContent", flag, id, emptyList<String>())
    }

    // endregion

    private suspend fun invokeSafely(
        jarUrl: String,
        key: String,
        ext: String,
        siteKey: String = "",
        action: (spider: Any) -> String,
    ): String =
        withContext(Dispatchers.IO) {
            runCatching { action(getSpider(jarUrl, key, ext, siteKey.ifBlank { key })) }.getOrElse { throwable ->
                val cause = (throwable as? InvocationTargetException)?.targetException ?: throwable
                AppLog.w(TAG, "爬虫调用失败 key=$key: $cause")
                throw IllegalStateException(
                    "爬虫调用失败 key=$key: ${cause.javaClass.simpleName}: ${cause.message ?: "未知原因"}",
                    cause,
                )
            }
        }

    /**
     * 获取（并缓存）Spider 实例，首次创建时以 ext 完成一次 init。
     * [key] 为爬虫类键（csp_XXX，决定加载哪个类），[siteKey] 为站点唯一标识
     * （对齐 FongMi siteKey 语义，同类的多个站点靠它隔离内部状态），缺省同 key。
     */
    private fun getSpider(jarUrl: String, key: String, ext: String, siteKey: String = key): Any {
        val context = appContext ?: error("SpiderRuntime 未初始化")
        // JS 爬虫（api 为 .js 地址、无 jar）：quickjs 引擎（FongMi 移植），jar 链路之前分流；
        // 本地包脚本（clan://）翻译为本地代理地址供引擎拉取
        if (isJsScript(key)) {
            val cacheKey = "js|$key|$ext|$siteKey"
            spiders[cacheKey]?.let { return it }
            synchronized(this) {
                spiders[cacheKey]?.let { return it }
                val spider = JsSpiderLoader.create(localizeScriptUrl(key), siteKey)
                spider.init(context, localizeExt(ext))
                spiders[cacheKey] = spider
                return spider
            }
        }
        // PY 爬虫（drpy .py 脚本）：系统 Python 子进程桥（FongMi 桌面方案，依赖本机 python + requests）
        if (isPyScript(key)) {
            val cacheKey = "py|$key|$ext|$siteKey"
            spiders[cacheKey]?.let { return it }
            synchronized(this) {
                spiders[cacheKey]?.let { return it }
                val spider = com.fongmi.chaquo.Spider(localizeScriptUrl(key)).also { it.siteKey = siteKey }
                spider.init(context, localizeExt(ext))
                spiders[cacheKey] = spider
                return spider
            }
        }
        require(jarUrl.isNotBlank() && key.isNotBlank()) { "爬虫源缺少 jar 或 key" }
        // csp_Bili 的 ext 运行时规范化（新版 Bili 爬虫要求 ext 带 type，源方老格式缺失时自动补齐）
        val effectiveExt = if (key == BILI_KEY) normalizeBiliExt(ext) else ext
        val cacheKey = "$jarUrl|$key|$effectiveExt|$siteKey"
        val missingKey = "$jarUrl|$key"
        missingSpiders[missingKey]?.let { expireAt ->
            // 重下验证过仍缺类：TTL 内快速失败，阻断逐请求「删缓存-重下-再失败」风暴
            if (System.currentTimeMillis() < expireAt) {
                error(
                    "爬虫类不存在 key=$key（已重下 jar 验证，" +
                        "${MISSING_SITE_RETRY_MILLIS / 60000} 分钟后自动重试）",
                )
            }
            missingSpiders.remove(missingKey, expireAt)
        }
        spiders[cacheKey]?.let { return it }
        synchronized(this) {
            spiders[cacheKey]?.let { return it }
            var converted = ensureConvertedJar(context, jarUrl)
            recentLoaderPath = converted
            val spider = runCatching { createSpider(context, converted, key, effectiveExt, siteKey) }.getOrElse { first ->
                // 类未找到：本地缓存可能损坏（转换产物或 dex jar 任一缺损，校验拦不住），
                // 删除两级缓存重新下载转换后再试一轮；其他错误原样抛出
                if (first.message?.startsWith("爬虫类未找到") != true) throw first
                AppLog.w(TAG, "爬虫类未找到，重下 jar 重试: $jarUrl")
                invalidateJar(jarUrl, converted)
                converted = ensureConvertedJar(context, jarUrl)
                recentLoaderPath = converted
                retrySpiderAfterRedownload(context, converted, key, effectiveExt, siteKey, missingKey)
            }
            spiders[cacheKey] = spider
            return spider
        }
    }

    /** js 脚本 api：http(s) 远程或本地包地址（clan://、file://）的 .js 文件 */
    private fun isJsScript(key: String): Boolean =
        isRemoteOrLocalScript(key) && key.substringBefore('?').endsWith(".js")

    /** py 脚本 api：http(s) 远程或本地包地址（clan://、file://）的 .py 文件 */
    private fun isPyScript(key: String): Boolean =
        isRemoteOrLocalScript(key) && key.substringBefore('?').endsWith(".py")

    private fun isRemoteOrLocalScript(key: String): Boolean = key.startsWith("http") ||
        key.startsWith(ClanPaths.PREFIX) || key.startsWith("file:")

    /** 本地包脚本地址翻译为本地代理 http 地址（quickjs/py 引擎经 okhttp 拉脚本） */
    private fun localizeScriptUrl(key: String): String =
        if (key.startsWith(ClanPaths.PREFIX)) ClanPaths.toProxyHttp(key, SpiderProxyServer.listeningPort) else key

    /** ext 内 clan://localhost/ 引用翻译为本地代理 /file 地址（jar/引擎经 okhttp 抓取本地文件） */
    private fun localizeExt(ext: String): String {
        val port = SpiderProxyServer.listeningPort
        if (port <= 0 || !ext.contains(ClanPaths.PREFIX)) return ext
        return CLAN_REF.replace(ext) { match -> ClanPaths.toProxyHttp(match.value, port) }
    }

    /** ext 内 clan 引用匹配：止于引号/$（$$$ 分隔段），容忍路径内空格与中文 */
    private val CLAN_REF = Regex("clan://localhost/[^\$\"']+")

    /** Bili 系站点 api 键 */
    private const val BILI_KEY = "csp_Bili"

    /** csp_Bili ext 规范化结果缓存（原 ext -> 补 type 后的 ext，免重复拉取分类配置） */
    private val biliExtCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * csp_Bili 的 ext 运行时规范化：新版 Bili 爬虫要求 ext 带 `type`（分类名 `#` 分隔），
     * 源方老格式 json（仅 class/filter）缺失时拉 json URL 取 type_name 拼接补齐；
     * 与导入器 normalizeBiliExt 同逻辑的运行时兜底（旧导入的 ext 无需重新导入配置）。
     * 字符串级处理避免 JSON 库依赖；拉取/解析失败返回原 ext。
     */
    private fun normalizeBiliExt(ext: String): String {
        if (ext.isBlank() || ext.contains("\"type\"")) return ext
        biliExtCache[ext]?.let { return it }
        val result = runCatching {
            val jsonUrl = Regex("\"json\"\\s*:\\s*\"([^\"]+)\"").find(ext)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") } ?: return@runCatching ext
            val body = SpiderHttp.client.newCall(
                okhttp3.Request.Builder().url(jsonUrl).build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val typeNames = Regex("\"type_name\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .filter { it.isNotBlank() }
                .toList()
            if (typeNames.isEmpty()) return@runCatching ext
            AppLog.i(TAG, "Bili ext 运行时补 type（${typeNames.size} 个分类）")
            ext.replaceFirst("{", "{\"type\":\"${typeNames.joinToString("#")}\",")
        }.getOrDefault(ext)
        biliExtCache[ext] = result
        return result
    }

    /**
     * 重下转换后的二次实例化：仍报「爬虫类未找到」说明该 key 确实不在 jar 内
     * （源配置与 jar 不匹配），记入失败缓存阻断重下风暴；TTL 到期自动放开，
     * 源方后续更新 jar 补上类即可恢复。
     */
    private fun retrySpiderAfterRedownload(
        context: Context,
        convertedPath: String,
        key: String,
        ext: String,
        siteKey: String,
        missingKey: String,
    ): Any =
        runCatching { createSpider(context, convertedPath, key, ext, siteKey) }.getOrElse { second ->
            if (second.message?.startsWith("爬虫类未找到") == true) {
                missingSpiders[missingKey] = System.currentTimeMillis() + MISSING_SITE_RETRY_MILLIS
                AppLog.w(TAG, "重下后仍无爬虫类 key=$key，${MISSING_SITE_RETRY_MILLIS / 60000} 分钟内快速失败")
            }
            throw second
        }

    /** 删除指定 jar 的两级缓存与 classloader（下次重新下载转换）；加固壳同步清理双产物与解包 guard */
    private fun invalidateJar(jarUrl: String, convertedPath: String) {
        jarFiles.remove(jarUrl)?.let { path ->
            runCatching { File(path).delete() }
        }
        // 先关闭 loader 释放 jar 文件句柄：Windows 上句柄未释放会阻塞产物删除与重转换
        runCatching { classLoaders.remove(convertedPath)?.close() }
        runCatching { File(convertedPath).delete() }
        guardArtifacts.remove(convertedPath)?.let { artifacts ->
            runCatching { File(artifacts.shellJar).delete() }
            runCatching { File(artifacts.guardFile).delete() }
        }
    }

    private fun createSpider(context: Context, convertedPath: String, key: String, ext: String, siteKey: String): Any {
        // 本地包 ext 内 clan:// 引用翻译为本地代理地址（实例缓存键用未翻译原串，跨重启端口无关）
        val ext = localizeExt(ext)
        val loader = getLoader(context, convertedPath)
        val clazz = findSpiderClass(loader, key) ?: findSpiderClass(reloadLoader(context, convertedPath), key)
            ?: error(
                "爬虫类未找到 key=$key（尝试: " +
                    "${SPIDER_PACKAGE_MODERN}$key、$SPIDER_CLASS_PACKAGE$key、$key）",
            )
        val spider = runCatching {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrElse { err ->
            // 静态初始化失败包装为 ExceptionInInitializerError、构造器抛错包装为
            // InvocationTargetException，两者自身 message 均无信息，沿 cause 链取根因展示
            val root = rootCauseOf(err)
            error("无法实例化爬虫类 ${clazz.name}: ${root.message ?: root.javaClass.name}")
        }
        // 对齐 FongMi：注入站点级 siteKey，并以 init(Context, ext) 两参签名初始化
        runCatching { clazz.getField("siteKey").set(spider, siteKey) }
            .onFailure { AppLog.w(TAG, "siteKey 注入失败 key=$siteKey: ${it.message}") }
        runCatching { invoke(spider, "init", context, ext) }
            .onFailure { AppLog.w(TAG, "爬虫 init 失败 key=$key: ${it.message}") }
        return spider
    }

    /** 丢弃缓存的 classloader 并重建：用于解密壳 jar 偶发失败后的重试 */
    private fun reloadLoader(context: Context, convertedPath: String): URLClassLoader {
        // 先关闭旧 loader 释放 jar 文件句柄，避免句柄残留阻塞后续产物替换
        runCatching { classLoaders.remove(convertedPath)?.close() }
        AppLog.w(TAG, "爬虫类查找失败，重载 classloader 重试: $convertedPath")
        return getLoader(context, convertedPath)
    }

    /**
     * 确保jar 已下载且完成 dex2jar 转换，返回转换产物路径：
     * 1. 下载 dex jar（md5 校验）到 files/spider/<md5(jarUrl)>.jar
     * 2. 加固壳 jar 走 guard 专用链路（返回 payload 产物路径）
     * 3. 普通 jar 按 dex jar 内容 md5 查找 cache/spider_converted/<fileMd5>.jar，无则转换
     */
    private fun ensureConvertedJar(context: Context, jarUrl: String): String {
        val dir = dataDir ?: error("SpiderRuntime 未初始化")
        val dexJar = ensureJar(dir, jarUrl)
        val dexFile = File(dexJar)
        val fileMd5 = fileMd5(dexFile)
        val convertedDir = File(File(dir, "cache"), CONVERTED_DIR).apply { mkdirs() }
        if (isGuardJar(dexFile)) {
            return ensureGuardConverted(dir, dexFile, fileMd5, convertedDir, jarUrl)
        }
        // 普通 JVM jar（无 dex、含 .class，部分本地包直接打类 jar）：URLClassLoader 可直载，无需转换
        if (isPlainJvmJar(dexFile)) {
            jarFiles[jarUrl] = dexJar
            AppLog.i(TAG, "普通 JVM jar 直载（无 dex）: ${dexFile.name}")
            return dexJar
        }
        val converted = File(convertedDir, "$fileMd5.jar")
        if (converted.exists() && converted.length() > 0L && isJarArchive(converted) &&
            sanitizeStampValid(converted)
        ) {
            jarFiles[jarUrl] = dexJar
            return converted.absolutePath
        }
        if (converted.exists()) {
            // 旧版本清洗逻辑的产物（缺当前版本标记）：清洗规则升级后必须重转换，否则
            // 带非法修饰的类永久残留在缓存（loadClass 成功的类不会再触发「类未找到」自愈）
            AppLog.i(TAG, "转换产物清洗版本过期，重转换: ${converted.name}")
            runCatching { classLoaders.remove(converted.absolutePath)?.close() }
            runCatching { converted.delete() }
        }
        AppLog.i(TAG, "dex2jar 转换开始: ${dexFile.name} -> ${converted.name}")
        val begin = System.currentTimeMillis()
        DexConverter.convert(dexFile, converted)
        AppLog.i(TAG, "dex2jar 转换完成（${converted.length()} 字节，耗时 ${System.currentTimeMillis() - begin}ms）")
        jarFiles[jarUrl] = dexJar
        return converted.absolutePath
    }

    /** 转换产物清洗版本标记条目名（DexConverter 写入；引用同一常量防写入/校验版本漂移） */
    private val SANITIZE_STAMP_ENTRY = DexConverter.SANITIZE_ENTRY

    /** 当前清洗规则版本（必须与 DexConverter.SANITIZE_VERSION 一致，2026-10-13 曾因漏改 "2"≠"3" 致每次启动重转换） */
    private val SANITIZE_STAMP_VERSION = DexConverter.SANITIZE_VERSION

    /** 校验转换产物带当前清洗版本标记（读取失败视为无效） */
    private fun sanitizeStampValid(jar: File): Boolean = runCatching {
        java.util.zip.ZipFile(jar).use { zip ->
            val entry = zip.getEntry(SANITIZE_STAMP_ENTRY) ?: return false
            zip.getInputStream(entry).bufferedReader().readText().trim() == SANITIZE_STAMP_VERSION
        }
    }.getOrDefault(false)

    /**
     * 加固壳 jar 转换：guard 资源条目本身是 ZIP 容器（内含 payload classes.dex，未加密），
     * 解包后与壳 jar 各自 dex2jar 转换，产出双产物（按壳 jar 内容 md5 命名）：
     * - <fileMd5>_payload.jar：payload 爬虫类所在（返回值，作为 classloader 缓存键）
     * - <fileMd5>_shell.jar：壳桥接类（DexNative/BaseSpiderGuard/Init 等，payload 引用）
     */
    private fun ensureGuardConverted(
        dir: File,
        dexFile: File,
        fileMd5: String,
        convertedDir: File,
        jarUrl: String,
    ): String {
        val payload = File(convertedDir, "$fileMd5$GUARD_PAYLOAD_SUFFIX")
        val shell = File(convertedDir, "$fileMd5$GUARD_SHELL_SUFFIX")
        val guardFile = File(File(File(dir, "cache"), GUARD_EXTRACT_DIR).apply { mkdirs() }, "$fileMd5.guard")
        jarFiles[jarUrl] = dexFile.absolutePath
        guardArtifacts[payload.absolutePath] = GuardArtifacts(shell.absolutePath, dexFile.absolutePath, guardFile.absolutePath)
        if (payload.exists() && payload.length() > 0L && isJarArchive(payload) &&
            shell.exists() && shell.length() > 0L && isJarArchive(shell)
        ) {
            // 旧版缓存可能缺 proxyInvoke 桥（幂等，已注入则快速跳过；新产物落地前已补丁）
            PayloadPatch.patchDexNativeProxyInvoke(payload)
            return payload.absolutePath
        }
        // 解包首个 guard 条目（本地条目头完好，但 deflate 数据可能被壳加密）
        extractGuardEntry(dexFile, guardFile)
        AppLog.i(TAG, "加固壳转换开始: ${dexFile.name} -> payload/shell 双产物")
        val begin = System.currentTimeMillis()
        // 先在暂存路径完成转换与补丁，再原子落到最终路径：最终文件一出现即为完整已补丁产物，
        // 多实例共享缓存目录时不会加载到半成品或未补丁 payload（DexNative 坏类会被 JVM 永久缓存）
        val payloadStaging = File(convertedDir, "$fileMd5$GUARD_PAYLOAD_SUFFIX.staging")
        val shellStaging = File(convertedDir, "$fileMd5$GUARD_SHELL_SUFFIX.staging")
        // 流式恢复优先（对齐饭太硬官方 jar 的明文容器）；失败则走 unidbg native 解密导出
        runCatching { DexConverter.convertGuard(guardFile, payloadStaging) }.getOrElse { streamErr ->
            AppLog.i(TAG, "guard 流式恢复失败（payload 数据加密），改走 native 解密: ${streamErr.message}")
            val dumps = FtyGuardEngine.dumpPayloadDex(dexFile, File(File(dir, "cache"), GUARD_ENGINE_DIR))
            convertDumpedPayload(dumps, payloadStaging)
        }
        DexConverter.convert(dexFile, shellStaging)
        // payload 版 DexNative 缺壳 native 的 proxyInvoke（B 站等代理分发总入口），落地前注入桥方法
        PayloadPatch.patchDexNativeProxyInvoke(payloadStaging)
        moveAtomically(payloadStaging, payload)
        moveAtomically(shellStaging, shell)
        AppLog.i(
            TAG,
            "加固壳转换完成（payload=${payload.length()} shell=${shell.length()} 字节，" +
                "耗时 ${System.currentTimeMillis() - begin}ms）",
        )
        return payload.absolutePath
    }

    /**
     * 将 native 解密导出的产物转换为 payload jar：PK 魔数容器直接流式转换，
     * 裸 dex 包一层 zip 后转换；逐个甄别直到产出有效 payload。
     */
    private fun convertDumpedPayload(dumps: List<ByteArray>, target: File) {
        require(dumps.isNotEmpty()) { "native 解密未产出任何数据" }
        // 工作目录按调用唯一化：多实例（跨进程）并发转换互不踩踏
        val tmpDir = File(target.parentFile, "native-dump-work-${System.nanoTime()}").apply { mkdirs() }
        try {
            var lastError: Throwable? = null
            dumps.forEachIndexed { index, bytes ->
                val source = when {
                    bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                        File(tmpDir, "dump_$index.zip").apply { writeBytes(bytes) }
                    bytes.size > 4 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
                        bytes[2] == 'x'.code.toByte() ->
                        File(tmpDir, "dump_$index.zip").apply {
                            ZipOutputStream(outputStream()).use { output ->
                                output.putNextEntry(ZipEntry("classes.dex"))
                                output.write(bytes)
                                output.closeEntry()
                            }
                        }
                    else -> return@forEachIndexed
                }
                runCatching { DexConverter.convertGuard(source, target) }.fold(
                    onSuccess = {
                        AppLog.i(TAG, "native 解密 payload 转换成功（dump#$index，${target.length()} 字节）")
                        return
                    },
                    onFailure = { lastError = it },
                )
            }
            error("native 解密产物均不可转换: ${lastError?.message ?: "无有效产物"}")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** 从壳 jar 提取首个 guard 资源条目（ZIP 容器）到目标文件；缺失抛异常 */
    private fun extractGuardEntry(dexFile: File, target: File) {
        if (target.exists() && target.length() > 0L) return
        ZipFile(dexFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(GUARD_ASSET_SUFFIX)) {
                    // 临时文件 + 原子替换：多实例并发解包不会读到半截 guard 容器
                    val temp = File(target.parentFile, target.name + "." + System.nanoTime() + ".staging")
                    zip.getInputStream(entry).use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    moveAtomically(temp, target)
                    return
                }
            }
        }
        error("壳 jar 内未找到 $GUARD_ASSET_SUFFIX 条目: ${dexFile.name}")
    }

    /** 普通 JVM jar 判定：ZIP 内无 classes*.dex 条目且含 .class 条目（部分本地包形态） */
    private fun isPlainJvmJar(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            var hasClass = false
            var hasDex = false
            val entries = zip.entries()
            while (entries.hasMoreElements() && !(hasClass && hasDex)) {
                val name = entries.nextElement().name
                if (Regex("classes[0-9]*\\.dex").matches(name)) hasDex = true
                if (name.endsWith(".class")) hasClass = true
            }
            hasClass && !hasDex
        }
    }.getOrDefault(false)

    /** 加固壳特征检测：jar 内同时存在 *.guard 资源条目与 ftyguard*.so 库条目 */
    private fun isGuardJar(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            var hasGuard = false
            var hasSo = false
            val entries = zip.entries()
            while (entries.hasMoreElements() && !(hasGuard && hasSo)) {
                val name = entries.nextElement().name
                if (name.endsWith(GUARD_ASSET_SUFFIX)) hasGuard = true
                if (name.endsWith(".so") && name.contains(GUARD_SO_KEYWORD)) hasSo = true
            }
            hasGuard && hasSo
        }
    }.getOrDefault(false)

    /**
     * dex jar 本地缓存：存在且魔数/md5 合法直接复用，否则经 OkHttp 下载（失败抛异常由上层兜底）。
     * 本地包 jar（clan://、file:// 或盘符绝对路径）：直用磁盘文件（md5 段存在时校验），不走下载缓存。
     */
    private fun ensureJar(dir: File, jarUrl: String): String {
        ClanPaths.toFile(jarUrl.substringBefore(";").trim())?.let { local ->
            if (!local.isFile) error("本地爬虫 jar 不存在: ${local.absolutePath}")
            val expectedMd5 = jarMd5(jarUrl)
            if (expectedMd5 != null && fileMd5(local) != expectedMd5) {
                error("本地爬虫 jar md5 校验失败: ${local.name}")
            }
            return local.absolutePath
        }
        jarFiles[jarUrl]?.let { path -> if (File(path).exists()) return path }
        val jarRoot = File(dir, JAR_DIR).apply { mkdirs() }
        val file = File(jarRoot, md5(jarUrl) + ".jar")
        val expectedMd5 = jarMd5(jarUrl)
        // 缓存文件校验：魔数非法（历史坏缓存如 HTML 错误页）或 md5 与配置不符（源方更新）时重新下载
        if (!isJarArchive(file) || (expectedMd5 != null && fileMd5(file) != expectedMd5)) {
            downloadJar(jarUrl, file)
        }
        jarFiles[jarUrl] = file.absolutePath
        return file.absolutePath
    }

    private fun downloadJar(jarUrl: String, target: File) {
        var lastError: Throwable? = null
        // 多候选地址轮询 + 单地址重试：偶发网络失败/错误页时自动恢复
        for (candidate in jarCandidates(jarUrl)) {
            repeat(DOWNLOAD_ATTEMPTS) {
                try {
                    downloadOnce(candidate, target)
                    return
                } catch (e: Throwable) {
                    lastError = e
                    target.delete()
                    File(target.absolutePath + ".tmp").delete()
                }
            }
        }
        throw IllegalStateException("爬虫 jar 下载失败：${lastError?.message ?: "未知错误"}", lastError)
    }

    private fun downloadOnce(url: String, target: File) {
        val request = Request.Builder().url(url).build()
        SpiderHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("爬虫 jar 下载失败 HTTP ${response.code}")
            val body = response.body ?: error("爬虫 jar 下载失败：空响应")
            val temp = File(target.absolutePath + ".tmp")
            body.byteStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!isJarArchive(temp)) {
                temp.delete()
                error("爬虫 jar 内容无效（非 jar/dex，可能为错误页）")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) error("爬虫 jar 缓存写入失败")
        }
    }

    /**
     * 解析 jar 多地址候选，对齐 FongMi 语义：
     * "url" 单地址；"url;md5;值(或http清单)" 主地址+校验；"url1;url2" 多备用地址轮询。
     * md5 段为校验信息不参与下载，其余 http 段作为备用候选。
     */
    private fun jarCandidates(jarUrl: String): List<String> {
        val parts = jarUrl.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) error("爬虫 jar 地址为空")
        val candidates = mutableListOf(parts.first())
        var index = 1
        while (index < parts.size) {
            if (parts[index].equals("md5", ignoreCase = true)) { index += 2; continue }
            if (parts[index].startsWith("http")) candidates += parts[index]
            index++
        }
        return candidates
    }

    /** 配置中的 jar md5（url;md5;<值|http清单>），用于缓存文件校验；无校验段返回 null */
    private fun jarMd5(jarUrl: String): String? {
        val parts = jarUrl.split(";").map { it.trim() }
        val index = parts.indexOfFirst { it.equals("md5", ignoreCase = true) }
        val value = parts.getOrNull(index + 1) ?: return null
        if (value.startsWith("http")) {
            return runCatching {
                val request = Request.Builder().url(value).build()
                SpiderHttp.client.newCall(request).execute().use { response ->
                    response.body?.string()?.trim()
                        ?.takeIf { it.length == 32 && !it.startsWith("http") }
                        ?.lowercase()
                }
            }.getOrNull()
        }
        return value.takeIf { it.length == 32 }?.lowercase()
    }

    /** 计算文件 md5（hex 小写），用于缓存文件与配置校验段的比对；失败返回空串触发重下 */
    private fun fileMd5(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** jar 内容校验：zip（PK 魔数，多数 jar）或裸 dex（"dex" 魔数），拦截 HTML 错误页等无效内容 */
    private fun isJarArchive(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(3)
                val read = input.read(head)
                read >= 2 && (
                    (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) ||
                        (read >= 3 && head[0] == 'd'.code.toByte() && head[1] == 'e'.code.toByte() && head[2] == 'x'.code.toByte())
                    )
            }
        }.getOrDefault(false)
    }

    /** 类加载器缓存：parent 为宿主类加载器（parent-first，桥接类优先生效）；加固壳走双 jar 分支 */
    private fun getLoader(context: Context, convertedPath: String): URLClassLoader =
        classLoaders.computeIfAbsent(convertedPath) { path ->
            val artifacts = guardArtifacts[path]
            if (artifacts != null) {
                buildGuardLoader(path, artifacts)
            } else {
                CrossJarLoader(path) {
                    // 跨 jar 委托候选：其他已注册的产物 loader（精简 jar 引用其他 jar 的公共类，
                    // 如 XYQHiker 所在 jar 缺 merge/A0/ya，依赖在同源全量 jar 里）
                    classLoaders.values.filter { it !== classLoaders[convertedPath] }.toList()
                }.also { invokeJarInit(it, context) }
            }
        }

    /**
     * 加固壳双 jar 类加载器（对齐探针验证结论）：
     * 1. FtyGuardEngine.init：常驻 unidbg 模拟器（全局单例，约 2s 仅首次），提供壳 native 解密能力
     * 2. InitOrigin.setClass(FtyGuardEngine)：壳 native getSpider 时的 InitOrigin.setClass(DexNative.class)
     *    在桌面 JVM 的等价注入，payload 混淆字符串解密经反射落到引擎 decrypt
     * 3. InitOrigin.init(context)：注入宿主上下文（部分站点 init 时取 SharedPreferences 依赖它）
     * 4. URLClassLoader(payload, shell)：payload 产物必须排在壳产物之前——payload 自带
     *    DexNative（弹幕/cookie 实现，静态块良性）会被壳 jar 里同名的 DexNative（静态块
     *    System.loadLibrary，JVM 上必然失败）遮蔽并毒化整个类，导致 init 阶段
     *    ExceptionInInitializerError、本地代理 do=ck/do=danmu 全部失败
     * 注意：不调用壳 Init.init（其内部走 native getLoader，JVM 上必然 UnsatisfiedLinkError）
     */
    private fun buildGuardLoader(payloadPath: String, artifacts: GuardArtifacts): URLClassLoader {
        val dir = dataDir ?: error("SpiderRuntime 未初始化")
        val context = appContext ?: error("SpiderRuntime 未初始化")
        FtyGuardEngine.init(File(artifacts.sourceJar), File(File(dir, "cache"), GUARD_ENGINE_DIR))
        val loader = URLClassLoader(
            arrayOf(File(payloadPath).toURI().toURL(), File(artifacts.shellJar).toURI().toURL()),
            DesktopSpiderRuntime::class.java.classLoader,
        )
        // 壳 native proxyInvoke 的 loader 兜底：桌面端壳 Init.loader() 无人填充，
        // native 经该加载器 loadClass 加载 payload 站点类处理代理请求
        FtyGuardEngine.setFallbackClassLoader(loader)
        runCatching {
            val initOrigin = loader.loadClass("com.github.catvod.spider.InitOrigin")
            initOrigin.getMethod("setClass", Class::class.java).invoke(null, FtyGuardEngine::class.java)
            initOrigin.getMethod("init", Context::class.java).invoke(null, context)
        }.onFailure { err ->
            val root = rootCauseOf(err)
            AppLog.w(TAG, "InitOrigin 注入失败: ${root.javaClass.simpleName}: ${root.message}")
        }
        AppLog.i(TAG, "加固壳 classloader 就绪: ${File(payloadPath).name}")
        return loader
    }

    /**
     * 对齐 FongMi JarLoader.invokeInit：loader 创建后、任何爬虫实例化前，
     * 调用 jar 自带的 com.github.catvod.spider.Init.init(Context)（静态），
     * 加固壳 jar 以此为引导入口完成自解密前的宿主环境注入。失败仅记录不阻断。
     */
    private fun invokeJarInit(loader: URLClassLoader, context: Context) {
        runCatching {
            val clazz = loader.loadClass("com.github.catvod.spider.Init")
            clazz.getMethod("init", Context::class.java).invoke(null, context)
        }.onFailure { err ->
            // 反射异常经 InvocationTargetException 包装（其 message 为 null），必须取根因
            val root = rootCauseOf(err)
            AppLog.w(TAG, "jar Init 引导失败: ${root.javaClass.simpleName}: ${root.message}")
        }
    }

    /** 沿 cause 链取最深层异常（带环保护），供错误提示定位根因 */
    private fun rootCauseOf(err: Throwable): Throwable {
        var current = err
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < 10) {
            current = current.cause ?: break
            depth++
        }
        return current
    }

    /**
     * key -> 类名候选：csp_ABC 依次尝试 spider 包（FongMi 主流 jar，如
     * com.github.catvod.spider.PanSearch）、crawler 包（CatVodOpen 老 jar，
     * 如 com.github.catvod.crawler.SpiderABC）、原值（完整类名）。
     * 加固壳站点 key 带 Guard 后缀（如 csp_AppSxGuard）：优先剥后缀定位
     * payload 爬虫类（com.github.catvod.spider.AppSx），壳 Guard 包装类不可 JVM 实例化。
     */
    private fun findSpiderClass(loader: URLClassLoader, key: String): Class<*>? {
        val candidates = buildList {
            if (key.startsWith(CSP_KEY_PREFIX)) {
                val suffix = key.removePrefix(CSP_KEY_PREFIX)
                suffix.removeSuffix(GUARD_SITE_SUFFIX).takeIf { it != suffix }?.let {
                    add(SPIDER_PACKAGE_MODERN + it)
                }
                add(SPIDER_PACKAGE_MODERN + suffix)
                add(SPIDER_CLASS_PACKAGE + suffix)
            }
            add(key)
        }
        for (name in candidates) {
            runCatching { loader.loadClass(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 反射调用爬虫方法：按方法名匹配声明方法，参数类型宽松适配
     * （Boolean/boolean、String、List、HashMap）；没有对应参数个数的重载时
     * 逐级丢弃尾部参数（兼容 searchContent 两参/三参）。
     * 方法缺失或执行异常均抛出带原因的异常，避免静默空数据难以排查。
     */
    private fun invoke(spider: Any, methodName: String, vararg args: Any?): String {
        val method = findMethod(spider.javaClass, methodName, args.size)
            ?: throw IllegalStateException("爬虫方法未找到: $methodName（${spider.javaClass.name}）")
        return runCatching {
            method.invoke(spider, *convertArgs(method.parameterTypes, args)) as? String ?: ""
        }.getOrElse { throwable ->
            val cause = (throwable as? InvocationTargetException)?.targetException ?: throwable
            throw IllegalStateException(
                "爬虫方法执行失败 $methodName: ${cause.javaClass.simpleName}: ${cause.message ?: "未知原因"}",
                cause,
            )
        }
    }

    /** 精确参数个数优先，其次逐级丢弃尾部参数 */
    private fun findMethod(clazz: Class<*>, methodName: String, argCount: Int): java.lang.reflect.Method? {
        val methods = clazz.methods.filter { it.name == methodName }
        val counts = listOf(argCount) + (argCount - 1 downTo 0).toList()
        for (count in counts) {
            methods.firstOrNull { it.parameterTypes.size == count }?.let { return it }
        }
        return null
    }

    /** 参数类型宽松适配：布尔/字符串/集合/映射，其余原样传入 */
    private fun convertArgs(paramTypes: Array<Class<*>>, args: Array<out Any?>): Array<Any?> =
        Array(paramTypes.size) { index ->
            val type = paramTypes[index]
            val value = args.getOrNull(index)
            when {
                value == null -> null
                type == Boolean::class.javaPrimitiveType || type == Boolean::class.java ->
                    value as? Boolean ?: false
                type == String::class.java -> value.toString()
                List::class.java.isAssignableFrom(type) && value is List<*> -> value
                Map::class.java.isAssignableFrom(type) && value is Map<*, *> -> HashMap(value)
                else -> value
            }
        }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 临时文件原子替换到目标路径：临时与目标同目录（同分区）；文件系统不支持原子移动时退回普通替换。
 */
private fun moveAtomically(temp: File, target: File) {
    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * 产物类类型校验器：SimpleVerifier 保留具体引用类型（BasicVerifier 将所有引用折叠为
 * REFERENCE_VALUE，无法发现 Object→具体类的赋值错误）。走 protected api 构造（public
 * 构造含防子类化检查会抛 IllegalStateException）；类层次经产物装载器解析（覆盖 getClass）。
 */
private class ProductVerifier(
    currentClass: org.objectweb.asm.Type,
    currentSuperClass: org.objectweb.asm.Type?,
    currentClassInterfaces: List<org.objectweb.asm.Type>,
    isInterface: Boolean,
    private val productLoader: ClassLoader,
) : org.objectweb.asm.tree.analysis.SimpleVerifier(
    org.objectweb.asm.Opcodes.ASM9,
    currentClass,
    currentSuperClass,
    currentClassInterfaces,
    isInterface,
) {
    override fun getClass(type: org.objectweb.asm.Type): Class<*>? = runCatching {
        Class.forName(type.internalName.replace('/', '.'), false, productLoader)
    }.getOrNull()
}

/**
 * 跨产物 jar 委托类加载器：TVBox 精简 jar（如 XYQHiker 所在 eed040a）引用其他产物 jar
 * 的公共类（merge/A0/ya 等），单 jar 独立 loader 断链（Android 端 DexClassLoader 共享
 * 虚拟机缓存无此问题）。parent 优先自身 jar；ClassNotFound 后委托 fallbackLoaders
 * 逐个尝试（解析中标记防循环委托）。
 */
private class CrossJarLoader(
    jarPath: String,
    private val fallbackLoaders: () -> List<ClassLoader>,
) : URLClassLoader(arrayOf(File(jarPath).toURI().toURL()), DesktopSpiderRuntime::class.java.classLoader) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        try {
            return super.loadClass(name, resolve)
        } catch (e: ClassNotFoundException) {
            val resolving = RESOLVING.get()
            if (name in resolving) throw e
            resolving.add(name)
            try {
                for (loader in fallbackLoaders()) {
                    try {
                        return loader.loadClass(name)
                    } catch (_: ClassNotFoundException) {
                    }
                }
                AppLog.w("CrossJarLoader", "跨 jar 委托失败: $name")
                throw e
            } finally {
                resolving.remove(name)
            }
        }
    }

    private companion object {
        /** 当前线程解析中的类名（防跨 jar 循环委托无限递归） */
        private val RESOLVING = ThreadLocal.withInitial { HashSet<String>() }
    }
}

/**
 * dex -> jar 转换（多 dex 逐个转换后合并；资源条目保留）。
 * API：de.femtopedia.dex2jar:dex-tools 的 com.googlecode.d2j.dex.Dex2jar.from(File).to(Path)。
 * 单个方法转换失败（个别爬虫的疑难字节码）跳过并告警，不阻断整体转换。
 */
object DexConverter {

    private val DEX_ENTRY = Regex("classes[0-9]*\\.dex")

    /** 清洗版本标记条目（ensureConvertedJar 校验：版本不符的旧产物强制重转换） */
    internal const val SANITIZE_ENTRY = "META-INF/SPS_SANITIZE"

    /** v7：补默认方法体（Absent Code）+ 产物装载器类名格式归一（层次解析曾全部失败致 <init> 错位未修），v6 产物需重转换 */
    internal const val SANITIZE_VERSION = "7"

    /** verify 修补轮次上限（单方法内多处类型不符逐轮修复，直至分析通过或放弃） */
    private const val VERIFY_REPAIR_ROUNDS = 8

    /**
     * dex 转换异常处理器：疑难类/方法失败跳过并告警（不设处理器时 dex2jar 内部
     * exceptionHandler 为 null 直接 NPE 中断整体转换，pg 等本地包 jar 必现）。
     */
    private val EXCEPTION_HANDLER = object : com.googlecode.d2j.dex.DexExceptionHandler {
        override fun handleFileException(e: Exception) {
            AppLog.w("DexConverter", "dex 转换告警（跳过失败部分继续）: ${e.message}")
        }

        override fun handleMethodTranslateException(
            method: com.googlecode.d2j.Method,
            methodNode: com.googlecode.d2j.node.DexMethodNode,
            visitor: org.objectweb.asm.MethodVisitor,
            e: Exception,
        ) {
            AppLog.w("DexConverter", "方法转换失败跳过 ${method}: ${e.message}")
        }
    }

    fun convert(sourceJar: File, targetJar: File) {
        // 工作目录按调用唯一化：多实例共享缓存目录时并发转换互不踩踏
        val tmpDir = File(targetJar.parentFile, "dex-work-${sourceJar.nameWithoutExtension}-${System.nanoTime()}")
            .apply { mkdirs() }
        val convertedJars = mutableListOf<File>()
        // 疑难类/方法转换失败跳过并告警（未设处理器时 dex2jar 内部 NPE 中断整体转换）
        val exceptionHandler = EXCEPTION_HANDLER
        ZipFile(sourceJar).use { zip ->
            val dexEntries = zip.entries().asSequence().filter { DEX_ENTRY.matches(it.name) }.toList()
            require(dexEntries.isNotEmpty()) {
                "jar 内未找到 classes*.dex（条目=${zip.entries().asSequence().take(8).map { it.name }.toList()}）"
            }
            for (entry in dexEntries) {
                val dexFile = File(tmpDir, entry.name)
                zip.getInputStream(entry).use { input -> dexFile.outputStream().use { input.copyTo(it) } }
                val outJar = File(tmpDir, entry.name + ".jar")
                val begin = System.currentTimeMillis()
                com.googlecode.d2j.dex.Dex2jar.from(dexFile)
                    .withExceptionHandler(exceptionHandler)
                    .to(outJar.toPath())
                AppLog.i(
                    "DexConverter",
                    "${entry.name} -> ${outJar.name}（${outJar.length()} 字节，${System.currentTimeMillis() - begin}ms）",
                )
                convertedJars += outJar
            }
        }
        // 合并：先原 jar 非 dex 资源，再各转换产物条目（跳过重复）；.class 条目清洗后写入；
        // 先写临时文件再原子替换，多实例共享缓存目录时不会读到半成品
        val temp = File(targetJar.parentFile, targetJar.name + "." + System.nanoTime() + ".staging")
        // 先读全量 class 字节做缺失构造器分析（dex2jar 丢弃伪 abstract <init>，子类 super 调用会 NoSuchMethodError），
        // 再逐条写入（缺失的 <init> 由合成委托构造器补齐）
        val classBytes = HashMap<String, ByteArray>()
        ZipInputStream(FileInputStream(sourceJar)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".class") && !DEX_ENTRY.matches(entry.name)) {
                    classBytes.putIfAbsent(entry.name, zip.readBytes())
                }
                entry = zip.nextEntry
            }
        }
        for (jar in convertedJars) {
            ZipFile(jar).use { part ->
                for (entry in part.entries()) {
                    if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                    classBytes.putIfAbsent(entry.name, part.getInputStream(entry).readBytes())
                }
            }
        }
        val analysis = missingInitSignatures(classBytes)
        if (analysis.missingInits.isNotEmpty()) {
            AppLog.i("DexConverter", "补造缺失构造器 ${analysis.missingInits.size} 个: ${analysis.missingInits.keys.take(5)}")
        }
        ZipOutputStream(temp.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(SANITIZE_ENTRY))
            output.write(SANITIZE_VERSION.toByteArray())
            output.closeEntry()
            val seen = mutableSetOf<String>()
            ZipFile(sourceJar).use { src ->
                for (entry in src.entries()) {
                    if (entry.isDirectory || DEX_ENTRY.matches(entry.name)) continue
                    if (!seen.add(entry.name)) continue
                    val bytes = classBytes[entry.name]
                    if (bytes != null) {
                        putConvertedEntry(output, entry.name, bytes, analysis)
                    } else {
                        putConvertedEntry(output, entry.name, src.getInputStream(entry), null)
                    }
                }
            }
            for (jar in convertedJars) {
                ZipFile(jar).use { part ->
                    for (entry in part.entries()) {
                        if (entry.isDirectory || !seen.add(entry.name)) continue
                        val bytes = classBytes[entry.name]
                        if (bytes != null) {
                            putConvertedEntry(output, entry.name, bytes, analysis)
                        } else {
                            putConvertedEntry(output, entry.name, part.getInputStream(entry), null)
                        }
                    }
                }
            }
        }
        moveAtomically(temp, targetJar)
        tmpDir.deleteRecursively()
    }

    /**
     * 加固壳 guard 容器专用转换（流式解析，抗损坏结构）。
     * 部分同族壳（如立播/全直播 jar 内 ftyshinidie.guard）故意破坏 ZIP 中央目录：
     * 签名改为 PK\x02\x01 并截断 EOCD，ZipFile 打开即抛 ZipException；但本地条目
     * （classes.dex）与 deflate 数据完好，ZipInputStream 按本地头顺序读取即可恢复。
     * 尾部损坏导致的 ZipException 按已收集条目正常收尾。产物只含转换后的 dex 类，
     * 不回拷容器内资源（META-INF 等对 payload 无意义）。
     */
    fun convertGuard(source: File, targetJar: File) {
        // 工作目录按调用唯一化：多实例共享缓存目录时并发转换互不踩踏
        val tmpDir = File(targetJar.parentFile, "dex-work-${source.nameWithoutExtension}-${System.nanoTime()}")
            .apply { mkdirs() }
        val convertedJars = mutableListOf<File>()
        try {
            ZipInputStream(source.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (DEX_ENTRY.matches(entry.name)) {
                        val dexFile = File(tmpDir, entry.name)
                        dexFile.outputStream().use { output -> zip.copyTo(output) }
                        val outJar = File(tmpDir, entry.name + ".jar")
                        val begin = System.currentTimeMillis()
                        com.googlecode.d2j.dex.Dex2jar.from(dexFile)
                            .withExceptionHandler(EXCEPTION_HANDLER)
                            .to(outJar.toPath())
                        AppLog.i(
                            "DexConverter",
                            "guard ${entry.name} -> ${outJar.name}（${outJar.length()} 字节，" +
                                "${System.currentTimeMillis() - begin}ms）",
                        )
                        convertedJars += outJar
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: ZipException) {
            // 中央目录区域被故意破坏：流式读取到本地条目全部收完后必然在此失败，属预期路径
            AppLog.i("DexConverter", "guard 容器尾部损坏（预期），已恢复 ${convertedJars.size} 个 dex 条目")
        }
        require(convertedJars.isNotEmpty()) { "guard 容器内未恢复出任何 classes*.dex: ${source.name}" }
        // 先写临时文件再原子替换：多实例共享缓存目录时不会读到半成品
        val temp = File(targetJar.parentFile, targetJar.name + "." + System.nanoTime() + ".staging")
        ZipOutputStream(temp.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(SANITIZE_ENTRY))
            output.write(SANITIZE_VERSION.toByteArray())
            output.closeEntry()
            for (jar in convertedJars) {
                ZipFile(jar).use { part ->
                    for (entry in part.entries()) {
                        if (entry.isDirectory) continue
                        putConvertedEntry(output, entry.name, part.getInputStream(entry), null)
                    }
                }
            }
        }
        moveAtomically(temp, targetJar)
        tmpDir.deleteRecursively()
    }

    /** 构造器非法修饰位：dex 伪修饰（native/synchronized/abstract 构造器）经 dex2jar 原样带出，JVM 校验拒收 */
    private const val ILLEGAL_INIT_MASK =
        org.objectweb.asm.Opcodes.ACC_NATIVE or
            org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED or
            org.objectweb.asm.Opcodes.ACC_ABSTRACT

    /**
     * 写入转换产物条目：.class 条目先清洗 `<init>` 的非法修饰位再写（如南风 Yoursmile jar 的
     * NetPan 构造器带 PUBLIC|NATIVE，触发 ClassFormatError 使整类不可加载、Bili 等依赖它的
     * 爬虫全部「类未找到」）；其余资源原样拷贝。清洗失败保留原字节（不因清洗阻断转换）。
     */
    private fun putConvertedEntry(
        output: ZipOutputStream,
        name: String,
        input: java.io.InputStream,
        analysis: InitAnalysis?,
    ) {
        putConvertedEntry(output, name, input.readBytes(), analysis)
    }

    private fun putConvertedEntry(
        output: ZipOutputStream,
        name: String,
        bytes: ByteArray,
        analysis: InitAnalysis?,
    ) {
        output.putNextEntry(ZipEntry(name))
        if (name.endsWith(".class")) {
            val cleaned = sanitizeIllegalInit(bytes, analysis?.missingInits ?: emptyMap())
            // 指令级修补：dex2jar 转换缺 checkcast / 匿名类父类指针错位 → VerifyError
            val repaired = repairVerify(cleaned, analysis?.classBytes ?: emptyMap())
            // 帧重算：dex2jar 产物个别方法缺 StackMapTable，加载即 VerifyError（XYQHiker/DJHub 实证）
            output.write(recomputeFrames(repaired, analysis))
        } else {
            output.write(bytes)
        }
        output.closeEntry()
    }

    /** verify 修补：可安全插入 checkcast 的「纯压栈」指令白名单（producer 到 invoke 间不得弹栈到 receiver 之下） */
    private val VERIFY_PUSH_WHITELIST = setOf(
        org.objectweb.asm.Opcodes.ALOAD,
        org.objectweb.asm.Opcodes.ILOAD,
        org.objectweb.asm.Opcodes.LLOAD,
        org.objectweb.asm.Opcodes.FLOAD,
        org.objectweb.asm.Opcodes.DLOAD,
        org.objectweb.asm.Opcodes.LDC,
        org.objectweb.asm.Opcodes.BIPUSH,
        org.objectweb.asm.Opcodes.SIPUSH,
        org.objectweb.asm.Opcodes.ACONST_NULL,
        org.objectweb.asm.Opcodes.ICONST_M1,
        org.objectweb.asm.Opcodes.ICONST_0,
        org.objectweb.asm.Opcodes.ICONST_1,
        org.objectweb.asm.Opcodes.ICONST_2,
        org.objectweb.asm.Opcodes.ICONST_3,
        org.objectweb.asm.Opcodes.ICONST_4,
        org.objectweb.asm.Opcodes.ICONST_5,
        org.objectweb.asm.Opcodes.LCONST_0,
        org.objectweb.asm.Opcodes.LCONST_1,
        org.objectweb.asm.Opcodes.FCONST_0,
        org.objectweb.asm.Opcodes.FCONST_1,
        org.objectweb.asm.Opcodes.FCONST_2,
        org.objectweb.asm.Opcodes.DCONST_0,
        org.objectweb.asm.Opcodes.DCONST_1,
        org.objectweb.asm.Opcodes.GETSTATIC,
        org.objectweb.asm.Opcodes.GETFIELD,
        org.objectweb.asm.Opcodes.DUP,
        org.objectweb.asm.Opcodes.DUP2,
        org.objectweb.asm.Opcodes.CHECKCAST,
    )

    /**
     * dex2jar 字节码类型修补：dex 指令集不做引用类型校验（iput-object 等任意引用直传），
     * dex2jar 转换后缺 JVM 要求的 checkcast，类加载即 VerifyError（merge 系爬虫批量实证）。
     * BasicVerifier 数据流分析定位类型不符点，按场景就地修补：
     * - putfield/putstatic：赋值类型与字段不符 → 前插 checkcast 字段类型
     * - areturn：返回值类型与签名不符 → 前插 checkcast 方法返回类型
     * - invokespecial <init>：匿名内部类父类指针错位（receiver 非 owner 子类）→ owner 改写为 receiver 的实际父类
     * - invokevirtual/invokeinterface：receiver 类型不符 → 在 receiver 压栈后插 checkcast
     *   （producer 到 invoke 之间须全为纯压栈指令，保证 checkcast 作用时栈顶即 receiver）
     * 修补后经 COMPUTE_FRAMES 重算帧；无法识别的场景保留原字节。
     */
    internal fun repairVerify(bytes: ByteArray, classBytes: Map<String, ByteArray>): ByteArray = try {
        val node = org.objectweb.asm.tree.ClassNode()
        org.objectweb.asm.ClassReader(bytes).accept(node, 0)
        // 产物类装载器：SimpleVerifier 类层次判定走真实类型（BasicVerifier 将所有引用折叠为
        // REFERENCE_VALUE，看不见 Object→具体类的赋值错误，与 JVM 校验不一致）
        val loader = object : ClassLoader() {
            private val cache = HashMap<String, Class<*>>()
            override fun findClass(name: String): Class<*> {
                cache[name]?.let { return it }
                // 调用方名字格式不一（Class.forName 点分 / ASM getCommonSuperClass 斜杠），统一为内部名查找
                val data = classBytes[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
                return defineClass(name.replace('/', '.'), data, 0, data.size).also { cache[name] = it }
            }
        }
        var repaired = false
        for (method in node.methods) {
            if (method.instructions.size() == 0) continue
            val currentClass = org.objectweb.asm.Type.getObjectType(node.name)
            val currentSuper = node.superName?.let { org.objectweb.asm.Type.getObjectType(it) }
            val currentInterfaces: List<org.objectweb.asm.Type> =
                node.interfaces.map { org.objectweb.asm.Type.getObjectType(it) }
            val isInterface = (node.access and org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0
            var round = 0
            while (round < VERIFY_REPAIR_ROUNDS) {
                round++
                val located: org.objectweb.asm.tree.AbstractInsnNode? = try {
                    val verifier = ProductVerifier(currentClass, currentSuper, currentInterfaces, isInterface, loader)
                    val frames = org.objectweb.asm.tree.analysis.Analyzer(verifier).analyze(node.name, method)
                    // 分析通过：全量扫描 <init> receiver-owner 相容性（Analyzer 不校验此项，JVM 校验拒收）
                    val sourceFrames = runCatching {
                        org.objectweb.asm.tree.analysis.Analyzer(org.objectweb.asm.tree.analysis.SourceInterpreter())
                            .analyze(node.name, method)
                    }.getOrNull()
                    if (scanInitOwner(method, frames, sourceFrames, loader)) repaired = true
                    null
                } catch (e: org.objectweb.asm.tree.analysis.AnalyzerException) {
                    e.node
                } catch (t: Throwable) {
                    AppLog.w("DexConverter", "verify 分析异常跳过 ${node.name}.${method.name}: ${t.message?.take(120)}")
                    null
                }
                if (located == null) break
                if (!repairInsn(node, method, located, loader)) {
                    AppLog.w("DexConverter", "verify 修补放弃: ${node.name}.${method.name} @${located.opcode} ${located.javaClass.simpleName}")
                    break
                }
                repaired = true
            }
        }
        if (repaired) {
            AppLog.i("DexConverter", "verify 修补: ${node.name}")
            org.objectweb.asm.ClassWriter(0).also { node.accept(it) }.toByteArray()
        } else {
            bytes
        }
    } catch (t: Throwable) {
        AppLog.w("DexConverter", "verify 修补失败保留原字节: ${t.message}")
        bytes
    }

    /**
     * 全量扫描 invokespecial <init> 的 receiver-owner 相容性并修正：
     * dex2jar 对匿名内部类（XYQHiker$2 实证）会把 <init> 调用 owner 解析成错误父类，
     * Analyzer 不校验 receiver 与 owner 的相容性但 JVM 校验拒收（Call to wrong <init> method）。
     * receiver（uninitialized new 值）不是 owner 子类时，owner 改写为其真实父类。
     */
    private fun scanInitOwner(
        method: org.objectweb.asm.tree.MethodNode,
        frames: Array<org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue>>,
        sourceFrames: Array<org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>>?,
        loader: ClassLoader,
    ): Boolean {
        var fixed = false
        for (insn in method.instructions) {
            if (insn.opcode != org.objectweb.asm.Opcodes.INVOKESPECIAL) continue
            val invoke = insn as org.objectweb.asm.tree.MethodInsnNode
            if (invoke.name != "<init>") continue
            val index = method.instructions.indexOf(insn)
            val frame = frames.getOrNull(index) ?: continue
            var argSlots = 0
            for (type in org.objectweb.asm.Type.getArgumentTypes(invoke.desc)) {
                argSlots += if (type.sort == org.objectweb.asm.Type.LONG || type.sort == org.objectweb.asm.Type.DOUBLE) 2 else 1
            }
            val receiverSlot = frame.stackSize - 1 - argSlots
            if (receiverSlot < 0) continue
            val receiver = frame.getStack(receiverSlot) as? org.objectweb.asm.tree.analysis.BasicValue ?: continue
            val have = receiver.type ?: continue
            val want = org.objectweb.asm.Type.getObjectType(invoke.owner)
            if (isAssignable(have, want, loader)) continue
            val cls = runCatching {
                Class.forName(have.internalName.replace('/', '.'), false, loader)
            }.getOrNull() ?: continue
            val sup = cls.superclass ?: continue
            val owner = sup.name.replace('.', '/')
            if (owner == invoke.owner) continue
            AppLog.i("DexConverter", "修正 <init> owner: ${have.internalName} ${invoke.owner} -> $owner")
            invoke.owner = owner
            fixed = true
        }
        return fixed
    }

    /** 类型相容判定：have 可赋给 want（层次经产物装载器解析；不可知时视为相容，不误改） */
    private fun isAssignable(have: org.objectweb.asm.Type, want: org.objectweb.asm.Type, loader: ClassLoader): Boolean = runCatching {
        val haveClass = Class.forName(have.internalName.replace('/', '.'), false, loader)
        val wantClass = Class.forName(want.internalName.replace('/', '.'), false, loader)
        wantClass.isAssignableFrom(haveClass)
    }.getOrDefault(true)

    /**
     * 对 AnalyzerException 定位到的类型不符指令就地修补：
     * - putfield/putstatic/areturn：栈顶即待校验值，前插 checkcast（字段/返回类型）
     * - invokespecial <init>：匿名内部类父类指针错位，owner 改写为 receiver（new 目标）的真实父类
     * - invokevirtual/invokeinterface：receiver 压栈后插 checkcast（producer 到 invoke 间须全为纯压栈指令）
     * 返回是否完成修补（场景无法识别时 false，调用方放弃该方法）。
     */
    private fun repairInsn(
        node: org.objectweb.asm.tree.ClassNode,
        method: org.objectweb.asm.tree.MethodNode,
        insn: org.objectweb.asm.tree.AbstractInsnNode,
        loader: ClassLoader,
    ): Boolean {
        val valueDesc = when (insn.opcode) {
            org.objectweb.asm.Opcodes.PUTFIELD, org.objectweb.asm.Opcodes.PUTSTATIC ->
                (insn as org.objectweb.asm.tree.FieldInsnNode).desc
            org.objectweb.asm.Opcodes.ARETURN ->
                org.objectweb.asm.Type.getReturnType(method.desc).descriptor
            else -> null
        }
        if (valueDesc != null) {
            val want = org.objectweb.asm.Type.getType(valueDesc)
            if (want.sort != org.objectweb.asm.Type.OBJECT && want.sort != org.objectweb.asm.Type.ARRAY) return false
            method.instructions.insertBefore(insn, org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST, want.internalName))
            return true
        }
        if (insn !is org.objectweb.asm.tree.MethodInsnNode) return false
        val isInitCall = insn.opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL && insn.name == "<init>"
        if (!isInitCall &&
            insn.opcode != org.objectweb.asm.Opcodes.INVOKEVIRTUAL &&
            insn.opcode != org.objectweb.asm.Opcodes.INVOKEINTERFACE
        ) {
            return false
        }
        if (isInitCall) {
            // 匿名内部类父类指针错位：<init> owner 改写为 receiver（new 目标）的真实父类。
            // receiver 压栈指令用 NEW/INVOKESPECIAL 配对平衡定位（参数里的 new+init 对以深度计抵消；
            // SourceInterpreter 的 producer 会落在 DUP 等复制指令上，不适用）
            var depth = 0
            var cursor: org.objectweb.asm.tree.AbstractInsnNode? = insn.previous
            while (cursor != null) {
                when (cursor.opcode) {
                    org.objectweb.asm.Opcodes.INVOKESPECIAL ->
                        if ((cursor as org.objectweb.asm.tree.MethodInsnNode).name == "<init>") depth++
                    org.objectweb.asm.Opcodes.NEW -> {
                        if (depth == 0) break
                        depth--
                    }
                }
                cursor = cursor.previous
            }
            val newInsn = cursor as? org.objectweb.asm.tree.TypeInsnNode ?: return false
            val superName = runCatching {
                Class.forName(newInsn.desc.replace('/', '.'), false, loader).superclass?.name?.replace('.', '/')
            }.getOrNull() ?: return false
            if (superName == insn.owner) return false
            insn.owner = superName
            return true
        }
        // receiver 类型不符：压栈后插 checkcast。producer 定位用 SourceInterpreter（宽松追踪，
        // 类型不符的方法仍可分析），producer 到 invoke 间须全为纯压栈指令，否则 checkcast
        // 作用时栈顶不是 receiver
        var argSlots = 0
        for (type in org.objectweb.asm.Type.getArgumentTypes(insn.desc)) {
            argSlots += if (type.sort == org.objectweb.asm.Type.LONG || type.sort == org.objectweb.asm.Type.DOUBLE) 2 else 1
        }
        val sourceFrames = runCatching {
            org.objectweb.asm.tree.analysis.Analyzer(org.objectweb.asm.tree.analysis.SourceInterpreter())
                .analyze(node.name, method)
        }.getOrNull() ?: return false
        val sourceFrame = sourceFrames.getOrNull(method.instructions.indexOf(insn)) ?: return false
        val receiverSlot = sourceFrame.stackSize - 1 - argSlots
        if (receiverSlot < 0) return false
        val receiver = sourceFrame.getStack(receiverSlot) as? org.objectweb.asm.tree.analysis.SourceValue ?: return false
        if (receiver.insns.size != 1) return false
        val producer = receiver.insns.first()
        var cursor: org.objectweb.asm.tree.AbstractInsnNode? = producer.next
        while (cursor != null && cursor !== insn) {
            if (cursor.opcode >= 0 && cursor.opcode !in VERIFY_PUSH_WHITELIST) return false
            cursor = cursor.next
        }
        method.instructions.insert(producer, org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST, insn.owner))
        return true
    }


    /**
     * 重算 StackMapTable：COMPUTE_FRAMES 全帧重建，修复 dex2jar 漏帧（分支目标无帧 → JVM
     * 校验拒收）。公共父类查询混合两级来源：jar 内类走分析期 superNames（不触发类加载），
     * 平台类走无初始化反射；接口多继承场景以 Object 兜底（罕见，帧声明父类型对校验合法）。
     */
    internal fun recomputeFrames(bytes: ByteArray, analysis: InitAnalysis?): ByteArray = try {
        val reader = org.objectweb.asm.ClassReader(bytes)
        val classBytes = analysis?.classBytes ?: emptyMap()
        // 产物类装载器：从分析期字节延迟 defineClass，getCommonSuperClass 走真实类层次
        // （此前 superNames 兜底 Object 算出错误帧 → Bad type on operand stack）
        val loader = object : ClassLoader() {
            private val cache = HashMap<String, Class<*>>()
            override fun findClass(name: String): Class<*> {
                cache[name]?.let { return it }
                val data = classBytes["$name.class"] ?: throw ClassNotFoundException(name)
                return defineClass(name.replace('/', '.'), data, 0, data.size).also { cache[name] = it }
            }
        }
        // 空构造 + accept：COMPUTE_FRAMES 才会真正重算帧；reader 构造是纯复制模式（曾因此产物缺帧）
        val writer = object : org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String): String {
                if (type1 == type2) return type1
                fun hierarchy(type: String): Set<String> {
                    val seen = HashSet<String>()
                    val queue = ArrayDeque<String>()
                    queue.add(type)
                    var hops = 0
                    while (queue.isNotEmpty() && hops < 64) {
                        val current = queue.removeFirst()
                        hops++
                        if (!seen.add(current)) continue
                        val cls = runCatching {
                            Class.forName(current.replace('/', '.'), false, loader)
                        }.getOrNull() ?: continue
                        cls.superclass?.name?.replace('.', '/')?.let { queue.add(it) }
                        cls.interfaces.forEach { queue.add(it.name.replace('.', '/')) }
                    }
                    return seen
                }
                val hierarchy2 = hierarchy(type2)
                return hierarchy(type1).firstOrNull { it in hierarchy2 } ?: "java/lang/Object"
            }
        }
        reader.accept(writer, 0)
        writer.toByteArray()
    } catch (t: Throwable) {
        AppLog.w("DexConverter", "帧重算失败保留原字节: ${t.message}")
        bytes
    }

    /** 全量 class 分析结果：缺失构造器（合成注入用）+ 直接父类 + 全量字节（帧重算的类型层次解析用） */
    internal class InitAnalysis(
        val missingInits: Map<String, Set<String>>,
        val superNames: Map<String, String>,
        val classBytes: Map<String, ByteArray>,
    )

    /**
     * 扫描全量 class 字节，找出「被 invokespecial 调用但继承链上无类声明」的 `<init>` 签名。
     * 背景：dex 允许伪 abstract 构造器（无方法体的 <init>），dex2jar 转换时直接丢弃该方法，
     * 子类 super 调用即 NoSuchMethodError（南风 Bili jar 的 J0.t 即此类，导致 B 站解析代理
     * 引导失败、playerContent 退化为裸 cid+aid 无法播放）。返回 类内部名 -> 需补造的构造器
     * 描述符集合；合成构造器自身也要调 super 同签名构造器，父类同样缺失则沿继承链向上
     * 传播补造（迭代至收敛）。
     */
    private fun missingInitSignatures(classBytes: Map<String, ByteArray>): InitAnalysis {
        val declaredInits = HashMap<String, MutableSet<String>>()
        val superNames = HashMap<String, String>()
        val internalNames = HashSet<String>()
        val called = HashSet<Pair<String, String>>()
        for (bytes in classBytes.values) {
            try {
                val reader = org.objectweb.asm.ClassReader(bytes)
                internalNames += reader.className
                reader.accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        superNames[name] = superName ?: "java/lang/Object"
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): org.objectweb.asm.MethodVisitor {
                        if (name == "<init>") {
                            declaredInits.getOrPut(reader.className) { HashSet() }.add(descriptor)
                        }
                        return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                name: String,
                                descriptor: String,
                                isInterface: Boolean,
                            ) {
                                if (name == "<init>" && opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL) {
                                    called += owner to descriptor
                                }
                            }
                        }
                    }
                }, 0)
            } catch (_: Throwable) {
                // 单类解析失败不影响整体分析（该类本来也无法通过 JVM 校验加载）
            }
        }
        if (called.isEmpty()) return InitAnalysis(emptyMap(), emptyMap(), classBytes)
        val missing = HashMap<String, MutableSet<String>>()
        var pending = called.toList()
        while (pending.isNotEmpty()) {
            val next = ArrayList<Pair<String, String>>()
            for ((owner, descriptor) in pending) {
                if (descriptor in (declaredInits[owner] ?: emptySet())) continue
                val bucket = missing.getOrPut(owner) { HashSet() }
                if (!bucket.add(descriptor)) continue
                val superName = superNames[owner] ?: continue
                if (superName in internalNames) next += superName to descriptor
            }
            pending = next
        }
        return InitAnalysis(missing, superNames, classBytes)
    }

    /**
     * 合成委托构造器：透传全部参数调用 super 的同签名 <init>（ACC_PUBLIC + RETURN）。
     * maxStack/maxLocals 均为 1(this) + 参数槽位（long/double 各占 2）。
     */
    private fun emitSyntheticInit(
        writer: org.objectweb.asm.ClassWriter,
        superName: String,
        descriptor: String,
    ) {
        val visitor = writer.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC,
            "<init>",
            descriptor,
            null,
            null,
        )
        visitor.visitCode()
        visitor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
        var slot = 1
        for (type in org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            visitor.visitVarInsn(type.getOpcode(org.objectweb.asm.Opcodes.ILOAD), slot)
            slot += if (type.sort == org.objectweb.asm.Type.LONG || type.sort == org.objectweb.asm.Type.DOUBLE) 2 else 1
        }
        visitor.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKESPECIAL,
            superName,
            "<init>",
            descriptor,
            false,
        )
        visitor.visitInsn(org.objectweb.asm.Opcodes.RETURN)
        visitor.visitMaxs(slot, slot)
        visitor.visitEnd()
    }

    /**
     * 清洗 class 内 dex 伪 native 方法（仅重建含非法位/伪 native 的类，其余原样返回）。
     * - `<init>` 带 NATIVE/SYNCHRONIZED/ABSTRACT 为非法修饰，清位后补「super() + return」空体
     *   （JVM 要求非 native/abstract 方法必须有 Code，且构造器必须调用父构造器防 VerifyError）
     * - 普通方法带 NATIVE（如南风 NetPan.init）：dex native 无方法体，清位后按返回类型补默认值空体；
     *   桌面运行时无 so 实现原本必然 UnsatisfiedLinkError，空体兜底反而可用
     */
    internal fun sanitizeIllegalInit(
        bytes: ByteArray,
        syntheticInits: Map<String, Set<String>> = emptyMap(),
    ): ByteArray = try {
        val reader = org.objectweb.asm.ClassReader(bytes)
        var dirty = false
        val writer = org.objectweb.asm.ClassWriter(0)
        reader.accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            // 直接父类名：伪 native 构造器补码的 super 调用目标（校验器要求 invokespecial <init>
            // 的目标类型与 uninitializedThis 匹配，硬编码 Object 在间接父类场景会被拒收）
            private val superName = reader.superName ?: "java/lang/Object"

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): org.objectweb.asm.FieldVisitor {
                // ConstantValue 规范化：JVM 仅允许基本类型与 String 字段携带字符串/数值初始值。
                // dex2jar 把 dex 中 null 初始值的引用类型字段（如 HashMap）错误转成空字符串
                // ConstantValue，类加载即抛 ClassFormatError: Bad string initial value（merge 系
                // 爬虫公共引导类 Init 高发，单类损坏导致整个 jar 全部爬虫不可用）。移除后字段为
                // null，与 dex 原语义一致。
                val safeValue = if (value != null && !constantValueMatches(descriptor, value)) {
                    dirty = true
                    null
                } else {
                    value
                }
                return writer.visitField(access, name, descriptor, signature, safeValue)
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): org.objectweb.asm.MethodVisitor {
                val isInit = name == "<init>"
                val illegalBits = if (isInit) access and ILLEGAL_INIT_MASK else 0
                val isNative = access and org.objectweb.asm.Opcodes.ACC_NATIVE != 0
                val isAbstract = access and org.objectweb.asm.Opcodes.ACC_ABSTRACT != 0
                if (illegalBits == 0 && !isNative) {
                    // 普通方法透传，但检测 dex2jar 转换失败丢弃的方法体（无 Code 且非 abstract，
                    // JVM 拒载：Absent Code attribute in method that is not native or abstract）
                    if (isAbstract) return writer.visitMethod(access, name, descriptor, signature, exceptions)
                    val delegate = writer.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, delegate) {
                        private var hasCode = false

                        override fun visitCode() {
                            hasCode = true
                            super.visitCode()
                        }

                        override fun visitEnd() {
                            if (!hasCode) {
                                dirty = true
                                AppLog.w("DexConverter", "补默认方法体: ${reader.className}.$name")
                                appendDefaultReturn(access, descriptor)
                            }
                            super.visitEnd()
                        }
                    }
                }
                dirty = true
                val cleaned = if (isInit) access and ILLEGAL_INIT_MASK.inv() else access and org.objectweb.asm.Opcodes.ACC_NATIVE.inv()
                val delegate = writer.visitMethod(cleaned, name, descriptor, signature, exceptions)
                return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, delegate) {
                    private var hasCode = false

                    override fun visitCode() {
                        hasCode = true
                        super.visitCode()
                    }

                    override fun visitEnd() {
                        if (!hasCode) {
                            if (isInit) {
                                // 无方法体的伪 native 构造器：补 super() 调用（免 VerifyError）后直接返回
                                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                                visitMethodInsn(
                                    org.objectweb.asm.Opcodes.INVOKESPECIAL,
                                    superName,
                                    "<init>",
                                    "()V",
                                    false,
                                )
                                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                                visitMaxs(1, maxLocals(cleaned, descriptor))
                            } else {
                                appendDefaultReturn(cleaned, descriptor)
                            }
                        }
                        super.visitEnd()
                    }
                }
            }
        }, 0)
        // 补造 dex2jar 丢弃的缺失构造器（伪 abstract <init>）：直接透传参数委托 super 同签名构造器
        val extraInits = syntheticInits[reader.className].orEmpty()
        for (descriptor in extraInits) {
            emitSyntheticInit(writer, reader.superName ?: "java/lang/Object", descriptor)
        }
        if (dirty || extraInits.isNotEmpty()) {
            if (extraInits.isNotEmpty()) {
                AppLog.i("DexConverter", "补造缺失构造器 ${reader.className}: ${extraInits.joinToString()}")
            }
            writer.toByteArray()
        } else {
            bytes
        }
    } catch (t: Throwable) {
        AppLog.w("DexConverter", "class 清洗失败保留原字节: ${t.message}")
        bytes
    }

    /**
     * ConstantValue 与字段描述符匹配判定（JVMS 4.7.16）：String 字段配 String、
     * 基本类型字段配对应包装值；引用类型（非 String）字段不允许携带 ConstantValue。
     */
    private fun constantValueMatches(descriptor: String, value: Any): Boolean = when (descriptor) {
        "Ljava/lang/String;" -> value is String
        "J" -> value is Long
        "F" -> value is Float
        "D" -> value is Double
        "I", "Z", "B", "C", "S" -> value is Int
        else -> false
    }

    /** locals 槽位数上限（max_locals 须容纳 this 与全部参数，否则 ClassFormatError: Arguments can't fit） */
    private fun maxLocals(access: Int, descriptor: String): Int {
        var count = if (access and org.objectweb.asm.Opcodes.ACC_STATIC != 0) 0 else 1
        for (type in org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            count += if (type.sort == org.objectweb.asm.Type.LONG || type.sort == org.objectweb.asm.Type.DOUBLE) 2 else 1
        }
        return count
    }

    /** 按返回类型补默认值返回序列（清除的 native 方法空体兜底） */
    private fun org.objectweb.asm.MethodVisitor.appendDefaultReturn(access: Int, descriptor: String) {
        when (org.objectweb.asm.Type.getReturnType(descriptor).sort) {
            org.objectweb.asm.Type.VOID -> visitInsn(org.objectweb.asm.Opcodes.RETURN)
            org.objectweb.asm.Type.BOOLEAN,
            org.objectweb.asm.Type.CHAR,
            org.objectweb.asm.Type.BYTE,
            org.objectweb.asm.Type.SHORT,
            org.objectweb.asm.Type.INT,
            -> {
                visitInsn(org.objectweb.asm.Opcodes.ICONST_0)
                visitInsn(org.objectweb.asm.Opcodes.IRETURN)
            }
            org.objectweb.asm.Type.LONG -> {
                visitInsn(org.objectweb.asm.Opcodes.LCONST_0)
                visitInsn(org.objectweb.asm.Opcodes.LRETURN)
            }
            org.objectweb.asm.Type.FLOAT -> {
                visitInsn(org.objectweb.asm.Opcodes.FCONST_0)
                visitInsn(org.objectweb.asm.Opcodes.FRETURN)
            }
            org.objectweb.asm.Type.DOUBLE -> {
                visitInsn(org.objectweb.asm.Opcodes.DCONST_0)
                visitInsn(org.objectweb.asm.Opcodes.DRETURN)
            }
            else -> {
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitInsn(org.objectweb.asm.Opcodes.ARETURN)
            }
        }
        visitMaxs(1, maxLocals(access, descriptor))
    }
}
