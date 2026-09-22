package com.shiping.desktop.data.spider

import android.content.Context
import com.shiping.desktop.spider.DesktopProxyRoutes
import com.shiping.desktop.util.AppLog
import com.shiping.shared.spider.SpiderEngine
import com.shiping.shared.spider.SpiderProxyBridge
import com.shiping.shared.spider.SpiderProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
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
        DesktopSpiderRuntime.dataDir = dataDir
        SpiderProxyServer.install(
            SpiderProxyBridge(
                onPortChanged = { port -> com.github.catvod.Proxy.set(port) },
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

    /** 单 jar 代理尝试：生态约定的 Proxy.proxy 优先，其后 ProxyOrigin.proxy（加固壳混入库） */
    private fun invokeJarProxy(jarPath: String, params: Map<String, String>): Array<Any>? {
        invokeStaticProxy(jarPath, params)?.let { return it }
        return invokeProxyOrigin(jarPath, params)
    }

    /** 调用 jar 内 com.github.catvod.spider.Proxy 的静态 proxy(Map) 方法 */
    private fun invokeStaticProxy(jarPath: String, params: Map<String, String>): Array<Any>? =
        runCatching {
            val loader = classLoaders[jarPath] ?: return null
            val clazz = loader.loadClass("com.github.catvod.spider.Proxy")
            val method = clazz.getMethod("proxy", Map::class.java)
            method.invoke(null, params) as? Array<Any>
        }.getOrElse { err ->
            if (err is InvocationTargetException) rethrowOrSkip(err.targetException) else null
        }

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
        }.getOrElse { err ->
            if (err is InvocationTargetException) rethrowOrSkip(err.targetException) else null
        }

    /**
     * 代理方法目标异常分流：业务异常（解析失败等）向上抛给请求方呈现；
     * 结构性错误（类被遮蔽毒化/方法缺失等 LinkageError 家族）视为该 jar 无处理能力，
     * 返回 null 交由分发器尝试下一个处理器。
     */
    private fun rethrowOrSkip(target: Throwable?): Array<Any>? {
        if (target == null) return null
        if (target is LinkageError) {
            AppLog.w(TAG, "代理处理跳过（结构性错误）: ${target.javaClass.simpleName}: ${target.message}")
            return null
        }
        throw target
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

    /** 搜索（TVBox 标准签名为 int pg） */
    override suspend fun searchContent(
        jarUrl: String,
        key: String,
        keyword: String,
        pg: Int,
        ext: String,
        siteKey: String,
    ): String = invokeSafely(jarUrl, key, ext, siteKey) { spider ->
        invoke(spider, "searchContent", keyword, false, pg)
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
        require(jarUrl.isNotBlank() && key.isNotBlank()) { "爬虫源缺少 jar 或 key" }
        val cacheKey = "$jarUrl|$key|$ext|$siteKey"
        spiders[cacheKey]?.let { return it }
        synchronized(this) {
            spiders[cacheKey]?.let { return it }
            var converted = ensureConvertedJar(context, jarUrl)
            recentLoaderPath = converted
            val spider = runCatching { createSpider(context, converted, key, ext, siteKey) }.getOrElse { first ->
                // 类未找到：本地缓存可能损坏（转换产物或 dex jar 任一缺损，校验拦不住），
                // 删除两级缓存重新下载转换后再试一轮；其他错误原样抛出
                if (first.message?.startsWith("爬虫类未找到") == true) {
                    AppLog.w(TAG, "爬虫类未找到，重下 jar 重试: $jarUrl")
                    invalidateJar(jarUrl, converted)
                    converted = ensureConvertedJar(context, jarUrl)
                    recentLoaderPath = converted
                    createSpider(context, converted, key, ext, siteKey)
                } else {
                    throw first
                }
            }
            spiders[cacheKey] = spider
            return spider
        }
    }

    /** 删除指定 jar 的两级缓存与 classloader（下次重新下载转换）；加固壳同步清理双产物与解包 guard */
    private fun invalidateJar(jarUrl: String, convertedPath: String) {
        jarFiles.remove(jarUrl)?.let { path ->
            runCatching { File(path).delete() }
        }
        classLoaders.remove(convertedPath)
        runCatching { File(convertedPath).delete() }
        guardArtifacts.remove(convertedPath)?.let { artifacts ->
            classLoaders.remove(artifacts.shellJar)
            runCatching { File(artifacts.shellJar).delete() }
            runCatching { File(artifacts.guardFile).delete() }
        }
    }

    private fun createSpider(context: Context, convertedPath: String, key: String, ext: String, siteKey: String): Any {
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
        classLoaders.remove(convertedPath)
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
        val converted = File(convertedDir, "$fileMd5.jar")
        if (converted.exists() && converted.length() > 0L && isJarArchive(converted)) {
            jarFiles[jarUrl] = dexJar
            return converted.absolutePath
        }
        AppLog.i(TAG, "dex2jar 转换开始: ${dexFile.name} -> ${converted.name}")
        val begin = System.currentTimeMillis()
        DexConverter.convert(dexFile, converted)
        AppLog.i(TAG, "dex2jar 转换完成（${converted.length()} 字节，耗时 ${System.currentTimeMillis() - begin}ms）")
        jarFiles[jarUrl] = dexJar
        return converted.absolutePath
    }

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
            return payload.absolutePath
        }
        // 解包首个 guard 条目（本地条目头完好，但 deflate 数据可能被壳加密）
        extractGuardEntry(dexFile, guardFile)
        AppLog.i(TAG, "加固壳转换开始: ${dexFile.name} -> payload/shell 双产物")
        val begin = System.currentTimeMillis()
        // 流式恢复优先（对齐饭太硬官方 jar 的明文容器）；失败则走 unidbg native 解密导出
        runCatching { DexConverter.convertGuard(guardFile, payload) }.getOrElse { streamErr ->
            AppLog.i(TAG, "guard 流式恢复失败（payload 数据加密），改走 native 解密: ${streamErr.message}")
            val dumps = FtyGuardEngine.dumpPayloadDex(dexFile, File(File(dir, "cache"), GUARD_ENGINE_DIR))
            convertDumpedPayload(dumps, payload)
        }
        DexConverter.convert(dexFile, shell)
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
    private fun convertDumpedPayload(dumps: List<ByteArray>, payload: File) {
        require(dumps.isNotEmpty()) { "native 解密未产出任何数据" }
        val tmpDir = File(payload.parentFile, "native-dump-work").apply { mkdirs() }
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
                runCatching { DexConverter.convertGuard(source, payload) }.fold(
                    onSuccess = {
                        AppLog.i(TAG, "native 解密 payload 转换成功（dump#$index，${payload.length()} 字节）")
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
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    return
                }
            }
        }
        error("壳 jar 内未找到 $GUARD_ASSET_SUFFIX 条目: ${dexFile.name}")
    }

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

    /** dex jar 本地缓存：存在且魔数/md5 合法直接复用，否则经 OkHttp 下载（失败抛异常由上层兜底） */
    private fun ensureJar(dir: File, jarUrl: String): String {
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
                URLClassLoader(arrayOf(File(path).toURI().toURL()), DesktopSpiderRuntime::class.java.classLoader)
                    .also { invokeJarInit(it, context) }
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
 * dex -> jar 转换（多 dex 逐个转换后合并；资源条目保留）。
 * API：de.femtopedia.dex2jar:dex-tools 的 com.googlecode.d2j.dex.Dex2jar.from(File).to(Path)。
 * 单个方法转换失败（个别爬虫的疑难字节码）跳过并告警，不阻断整体转换。
 */
object DexConverter {

    private val DEX_ENTRY = Regex("classes[0-9]*\\.dex")

    fun convert(sourceJar: File, targetJar: File) {
        val tmpDir = File(targetJar.parentFile, "dex-work-${sourceJar.nameWithoutExtension}").apply { mkdirs() }
        val convertedJars = mutableListOf<File>()
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
                com.googlecode.d2j.dex.Dex2jar.from(dexFile).to(outJar.toPath())
                AppLog.i(
                    "DexConverter",
                    "${entry.name} -> ${outJar.name}（${outJar.length()} 字节，${System.currentTimeMillis() - begin}ms）",
                )
                convertedJars += outJar
            }
        }
        // 合并：先原 jar 非 dex 资源，再各转换产物条目（跳过重复）
        ZipOutputStream(targetJar.outputStream()).use { output ->
            val seen = mutableSetOf<String>()
            ZipFile(sourceJar).use { src ->
                for (entry in src.entries()) {
                    if (entry.isDirectory || DEX_ENTRY.matches(entry.name)) continue
                    if (!seen.add(entry.name)) continue
                    output.putNextEntry(ZipEntry(entry.name))
                    src.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            for (jar in convertedJars) {
                ZipFile(jar).use { part ->
                    for (entry in part.entries()) {
                        if (entry.isDirectory || !seen.add(entry.name)) continue
                        output.putNextEntry(ZipEntry(entry.name))
                        part.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
        }
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
        val tmpDir = File(targetJar.parentFile, "dex-work-${source.nameWithoutExtension}").apply { mkdirs() }
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
                        com.googlecode.d2j.dex.Dex2jar.from(dexFile).to(outJar.toPath())
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
        ZipOutputStream(targetJar.outputStream()).use { output ->
            for (jar in convertedJars) {
                ZipFile(jar).use { part ->
                    for (entry in part.entries()) {
                        if (entry.isDirectory) continue
                        output.putNextEntry(ZipEntry(entry.name))
                        part.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
        }
        tmpDir.deleteRecursively()
    }
}
