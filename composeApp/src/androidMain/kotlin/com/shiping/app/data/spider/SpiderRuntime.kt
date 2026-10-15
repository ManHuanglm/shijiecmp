package com.shiping.app.data.spider

import android.content.Context
import android.util.Log
import com.fongmi.chaquo.Loader
import com.shiping.desktop.data.repository.ClanPaths
import com.shiping.shared.spider.SpiderEngine
import com.shiping.shared.spider.SpiderProxyBridge
import com.shiping.shared.spider.SpiderProxyServer
import com.shiping.desktop.data.spider.SpiderProxyPort
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Android TVBox 爬虫源运行时（实现 commonMain SpiderEngine）：
 * - 按站点 jar 地址下载并缓存到 filesDir/spider/<md5(jarUrl)>.jar，已存在直接复用
 * - DexClassLoader（parent-first）加载，使 jar 内 com.github.catvod.* 解析到宿主桥接类
 * - 反射调用 Spider 子类的 homeContent/detailContent 等方法（JSON 字符串进出）
 * - 本地代理经 SpiderProxyBridge 注入（端口写入 SpiderProxyPort 供 SpiderMapper 改写占位地址）
 */
object SpiderRuntime : SpiderEngine {

    private const val TAG = "SpiderRuntime"

    /** jar 缓存目录（filesDir 下） */
    private const val JAR_DIR = "spider"

    /** dex 优化与 native 库解压目录（cacheDir 下，对齐 FongMi：odex 与 librarySearchPath 同目录） */
    private const val ODEX_DIR = "spider_odex"

    /** 爬虫站点 key 前缀（如 csp_XXX） */
    private const val CSP_KEY_PREFIX = "csp_"

    /** Spider 子类所在包名前缀（老 jar：CatVodOpen 约定） */
    private const val SPIDER_CLASS_PACKAGE = "com.github.catvod.crawler.Spider"

    /** Spider 子类所在包名前缀（主流 jar：FongMi 约定，如 csp_PanSearch -> spider.PanSearch） */
    private const val SPIDER_PACKAGE_MODERN = "com.github.catvod.spider."

    /** 每个 jar 候选地址的下载尝试次数（偶发网络失败/错误页自愈） */
    private const val DOWNLOAD_ATTEMPTS = 2

    @Volatile
    private var appContext: Context? = null

    /** jar 本地路径缓存：jarUrl -> 文件绝对路径 */
    private val jarFiles = ConcurrentHashMap<String, String>()

    /** 类加载器缓存：jarPath -> DexClassLoader */
    private val classLoaders = ConcurrentHashMap<String, DexClassLoader>()

    /** Spider 实例缓存：jarUrl|key|ext -> 实例（init 只调用一次，ext 不同视为不同配置） */
    private val spiders = ConcurrentHashMap<String, Any>()

    /** 最近使用的 jar 本地路径（本地代理请求优先分发给该 jar 的静态 Proxy 类） */
    @Volatile
    private var recentLoaderPath: String? = null

    /** 注入宿主上下文（AppContainer 初始化时调用），同时安装代理桥并启动本地代理服务 */
    fun init(context: Context) {
        appContext = context.applicationContext
        // 本地包根目录候选：TVBox 约定存储根（/sdcard）+ 应用专属目录
        ClanPaths.setRelativeRoots(
            android.os.Environment.getExternalStorageDirectory(),
            File("/sdcard"),
            context.getExternalFilesDir(null),
            context.filesDir,
        )
        installSpiderThreadGuard()
        SpiderProxyServer.install(
            SpiderProxyBridge(
                onPortChanged = { port ->
                    // catvod Java 桥经反射调用（KMP android target 的 kotlinc 不解析 Java 源，
                    // 编译期不可见；运行时类已由 javac 编入 APK）
                    runCatching {
                        Class.forName("com.github.catvod.Proxy")
                            .getMethod("set", Int::class.javaPrimitiveType)
                            .invoke(null, port)
                    }
                    // 同步 jvmCommon 端口状态：SpiderMapper 改写占位代理地址用
                    SpiderProxyPort.current = port
                },
                dispatchProxy = { params -> proxyInvoke(params) },
                // /hdr 直链 header 注入路由（对齐桌面）：Cookie 等超出 UA/Referer 的
                // header 经 PlaybackUrlResolver 包装为本地代理地址流式转发
                extraRouteHandler = { session -> AndroidProxyRoutes.handle(session) },
                logger = { level, tag, message ->
                    when (level) {
                        'w' -> Log.w(tag, message)
                        'e' -> Log.e(tag, message)
                        else -> Log.i(tag, message)
                    }
                },
            ),
        )
        SpiderProxyServer.start()
    }

    /**
     * 爬虫线程异常守卫：payload 爬虫 jar 内自建线程池（pool-N-*）跑 native/解析逻辑时，
     * 未捕获异常（如 jar 自带 .so 缺失的 UnsatisfiedLinkError）不经宿主协程管控，
     * 默认处理器会直接杀死整个进程（2026-10-14 模拟器聚合搜索实证）。守卫按线程名与
     * 调用栈包名识别爬虫来源线程仅记日志，其余异常仍交系统默认处理（正常闪退路径不变）。
     */
    private fun installSpiderThreadGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val fromSpider = thread.name.startsWith("pool-") ||
                throwable.stackTrace.any {
                    it.className.startsWith("com.github.catvod") || it.className.startsWith("com.fongmi")
                }
            if (fromSpider) {
                Log.w(TAG, "爬虫线程异常拦截 thread=${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}")
            } else {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * 本地代理请求分发（对齐 FongMi JarLoader.proxy）：
     * 1. 优先用最近使用 jar 的 com.github.catvod.spider.Proxy.proxy(Map) 静态方法（生态约定）
     * 2. 其余已加载 jar 的同名静态类依次尝试
     * 3. 最后兜底遍历 Spider 实例的 proxy 实例方法
     * 返回 Object[] {code, mimeType, InputStream, headers?} 或 null（无爬虫处理）。
     */
    override fun proxyInvoke(params: Map<String, String>): Array<Any>? {
        recentLoaderPath?.let { path ->
            invokeStaticProxy(path, params)?.let { return it }
        }
        for (path in classLoaders.keys) {
            if (path == recentLoaderPath) continue
            invokeStaticProxy(path, params)?.let { return it }
        }
        for (spider in spiders.values) {
            try {
                val method = spider.javaClass.getMethod("proxy", Map::class.java)
                (method.invoke(spider, params) as? Array<Any>)?.let { return it }
            } catch (_: NoSuchMethodException) {
                // 该爬虫未实现 proxy，属于预期探测，继续尝试下一个
            } catch (e: Exception) {
                Log.w("SpiderRuntime", "爬虫 proxy 调用失败", e)
            }
        }
        return null
    }

    /** 调用 jar 内 com.github.catvod.spider.Proxy 的静态 proxy(Map) 方法 */
    private fun invokeStaticProxy(jarPath: String, params: Map<String, String>): Array<Any>? =
        runCatching {
            val loader = classLoaders[jarPath] ?: return null
            val clazz = loader.loadClass("com.github.catvod.spider.Proxy")
            val method = clazz.getMethod("proxy", Map::class.java)
            method.invoke(null, params) as? Array<Any>
        }.getOrElse { err ->
            if (err is InvocationTargetException) throw err.targetException ?: err else null
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
        siteKey: String,
        action: (spider: Any) -> String,
    ): String =
        withContext(Dispatchers.IO) {
            runCatching { action(getSpider(jarUrl, key, ext, siteKey.ifBlank { key })) }.getOrElse { throwable ->
                val cause = (throwable as? InvocationTargetException)?.targetException ?: throwable
                Log.w(TAG, "爬虫调用失败 key=$key: $cause", cause)
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
        // JS 爬虫（api 为 .js 地址、无 jar）：quickjs 引擎（FongMi 移植），jar 链路之前分流
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
        // PY 爬虫（drpy .py 脚本）：Chaquopy 内嵌 Python 引擎（chaquo 模块，FongMi 移植方案）
        if (isPyScript(key)) {
            val cacheKey = "py|$key|$ext|$siteKey"
            spiders[cacheKey]?.let { return it }
            synchronized(this) {
                spiders[cacheKey]?.let { return it }
                val spider = Loader().spider(localizeScriptUrl(key))
                spider.siteKey = siteKey
                spider.init(context, localizeExt(ext))
                spiders[cacheKey] = spider
                return spider
            }
        }
        require(jarUrl.isNotBlank() && key.isNotBlank()) { "爬虫源缺少 jar 或 key" }
        val cacheKey = "$jarUrl|$key|$ext|$siteKey"
        spiders[cacheKey]?.let { return it }
        synchronized(this) {
            spiders[cacheKey]?.let { return it }
            var jarPath = ensureJar(context, jarUrl)
            recentLoaderPath = jarPath
            val spider = runCatching { createSpider(context, jarPath, key, ext, siteKey) }.getOrElse { first ->
                // 类未找到：本地缓存 jar 可能损坏（zip 魔数合法但 dex 内容缺损，校验拦不住），
                // 删除缓存重新下载后再试一轮；其他错误原样抛出
                if (first.message?.startsWith("爬虫类未找到") == true) {
                    Log.w(TAG, "爬虫类未找到，重下 jar 重试: $jarUrl")
                    invalidateJar(context, jarUrl)
                    jarPath = ensureJar(context, jarUrl)
                    recentLoaderPath = jarPath
                    createSpider(context, jarPath, key, ext, siteKey)
                } else {
                    throw first
                }
            }
            spiders[cacheKey] = spider
            return spider
        }
    }

    /** 删除指定 jar 的本地缓存与 classloader（下次 ensureJar 重新下载） */
    private fun invalidateJar(context: Context, jarUrl: String) {
        jarFiles.remove(jarUrl)?.let { path ->
            classLoaders.remove(path)
            runCatching { File(path).delete() }
        }
    }

    private fun createSpider(context: Context, jarPath: String, key: String, ext: String, siteKey: String): Any {
        // 本地包 ext 内 clan:// 引用翻译为本地代理地址（实例缓存键用未翻译原串，跨重启端口无关）
        val ext = localizeExt(ext)
        // 首次找类失败时重载 classloader 再试一轮：锁壳 jar 的解密由壳在 Init.init 中完成，
        // 偶发失败（网络/时序）会导致真实类未解出，重建 loader 重新走一次解密引导
        val loader = getLoader(context, jarPath)
        val clazz = findSpiderClass(loader, key) ?: findSpiderClass(reloadLoader(context, jarPath), key)
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
            // 加固壳 jar（Guard 后缀）内部吞掉真实错误后自抛 NPE，给出场景提示
            val hint = if (root is NullPointerException && root.message?.contains("getMessage()") == true) {
                "（jar 自身吞掉了真实错误，常见于加密锁壳 jar，可能无法在第三方应用运行）"
            } else {
                ""
            }
            error("无法实例化爬虫类 ${clazz.name}: ${root.message ?: root.javaClass.name}$hint")
        }
        // 对齐 FongMi：注入站点级 siteKey，并以 init(Context, ext) 两参签名初始化
        runCatching { clazz.getField("siteKey").set(spider, siteKey) }
            .onFailure { Log.w(TAG, "siteKey 注入失败 key=$siteKey: ${it.message}") }
        runCatching { invoke(spider, "init", context, ext) }
            .onFailure { Log.w(TAG, "爬虫 init 失败 key=$key: ${it.message}") }
        return spider
    }

    /** 丢弃缓存的 classloader 并重建：用于锁壳 jar 解密偶发失败后的重试 */
    private fun reloadLoader(context: Context, jarPath: String): DexClassLoader {
        classLoaders.remove(jarPath)
        Log.w(TAG, "爬虫类查找失败，重载 classloader 重试: $jarPath")
        return getLoader(context, jarPath)
    }
    /** js 脚本 api：http(s) 远程或本地包地址（clan://、file://）的 .js 文件 */
    private fun isJsScript(key: String): Boolean =
        isScriptPath(key) && key.substringBefore('?').endsWith(".js")

    /** py 脚本 api：http(s) 远程或本地包地址（clan://、file://）的 .py 文件 */
    private fun isPyScript(key: String): Boolean =
        isScriptPath(key) && key.substringBefore('?').endsWith(".py")

    private fun isScriptPath(key: String): Boolean = key.startsWith("http") ||
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
    private val CLAN_REF = Regex("clan://localhost/[^\$\'\"]+")

    /** jar 本地缓存：存在且魔数/md5 合法直接复用，否则经 OkHttp 下载（失败抛异常由上层兜底） */
    private fun ensureJar(context: Context, jarUrl: String): String {
        // 本地包 jar（clan://localhost/、file:// 或盘符路径）：复制到应用缓存后加载
        //（Android 10+ 禁止加载应用可写存储上的 dex，共享存储文件须落地应用目录再置只读）
        ClanPaths.toFile(jarUrl.substringBefore(";").trim())?.let { local ->
            if (!local.isFile) error("本地爬虫 jar 不存在: ${local.absolutePath}")
            val expectedMd5 = jarMd5(jarUrl)
            if (expectedMd5 != null && fileMd5(local) != expectedMd5) {
                error("本地爬虫 jar md5 校验失败: ${local.name}")
            }
            val dir = File(context.filesDir, JAR_DIR).apply { mkdirs() }
            val cached = File(dir, md5(jarUrl) + ".jar")
                local.copyTo(cached, overwrite = true)
                // 自杀守卫中和：改写 dex 内 killProcess 字符串，守卫线程届时 NoSuchMethodError 自然死亡
                cached.writeBytes(com.shiping.shared.spider.DexGuardPatch.patchJar(cached.readBytes()))
            if (!cached.setReadOnly()) Log.w(TAG, "爬虫 jar 置只读失败: ${cached.name}")
            jarFiles[jarUrl] = cached.absolutePath
            return cached.absolutePath
        }
        jarFiles[jarUrl]?.let { path -> if (File(path).exists()) return path }
        val dir = File(context.filesDir, JAR_DIR).apply { mkdirs() }
        val file = File(dir, md5(jarUrl) + ".jar")
        val expectedMd5 = jarMd5(jarUrl)
        // 缓存文件校验：魔数非法（历史坏缓存如 HTML 错误页）或 md5 与配置不符（源方更新）时重新下载
        if (!isJarArchive(file) || (expectedMd5 != null && fileMd5(file) != expectedMd5)) {
            downloadJar(jarUrl, file)
        }
        // Android 10+ 强制 W^X：可写文件不允许作为 dex 加载
        // （SecurityException: Writable dex file is not allowed），加载前必须置只读
        if (!file.setReadOnly()) Log.w(TAG, "爬虫 jar 置只读失败: ${file.name}")
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

    /** 类加载器缓存：parent 为宿主类加载器（parent-first，桥接类优先生效） */
    private fun getLoader(context: Context, jarPath: String): DexClassLoader =
        classLoaders.computeIfAbsent(jarPath) { path ->
            val cacheDir = File(context.cacheDir, ODEX_DIR).apply { mkdirs() }
            val loader = DexClassLoader(path, cacheDir.absolutePath, cacheDir.absolutePath, SpiderRuntime::class.java.classLoader)
            invokeJarInit(loader, context)
            loader
        }

    /**
     * 对齐 FongMi JarLoader.invokeInit：loader 创建后、任何爬虫实例化前，
     * 调用 jar 自带的 com.github.catvod.spider.Init.init(Context)（静态），
     * 加固壳 jar 以此为引导入口完成自解密前的宿主环境注入。失败仅记录不阻断。
     */
    private fun invokeJarInit(loader: DexClassLoader, context: Context) {
        runCatching {
            val clazz = loader.loadClass("com.github.catvod.spider.Init")
            clazz.getMethod("init", Context::class.java).invoke(null, context)
        }.onFailure { Log.w(TAG, "jar Init 引导失败: ${it.message}") }
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
     */
    private fun findSpiderClass(loader: DexClassLoader, key: String): Class<*>? {
        val candidates = buildList {
            if (key.startsWith(CSP_KEY_PREFIX)) {
                val suffix = key.removePrefix(CSP_KEY_PREFIX)
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
