package com.shiping.desktop.data.repository

import java.io.File
import java.net.URLEncoder

/**
 * TVBox 本地包路径协议工具（对齐 TVBox clan://localhost/ 约定）：
 * 本地包配置以 json 所在目录为基准，导入时把 ./ 相对引用改写为 clan://localhost/<绝对路径>
 * 稳定入库（跨重启不依赖代理端口）；同时兼容 TVBox 生态三种本地形态——
 * - clan://localhost/<绝对路径>（盘符形态）
 * - clan://localhost/<相对路径> / file://<相对路径> / http://127.0.0.1:9978/file/<相对路径>
 *   （TVBox 存储根形态，运行时按「本地包根目录」候选解析）
 * 运行时按消费方翻译——宿主直读处（jar 缓存、配置拉取）转本地文件，
 * jar/引擎抓取处（ext 内路径、js/py 脚本地址）转本地代理 /file 路由的 http 地址。
 */
object ClanPaths {

    /** 本地包协议前缀，后接文件系统绝对路径（clan://localhost/C:/pkg/lib/x.json） */
    const val PREFIX = "clan://localhost/"

    /** 本地代理文件路由路径（DesktopProxyRoutes /file，p 参数承载绝对路径） */
    private const val FILE_ROUTE = "/file?p="

    /** TVBox 本地服务器文件路由（http://127.0.0.1:9978/file/<存储根相对路径>），导入时改写为 clan 形态 */
    private val TVBOX_SERVER_FILE = Regex("""http://127\.0\.0\.1:\d+/file/(\S+?)(?=["\s$]|$)""")

    /** 相对形态（clan://localhost/rel、file://rel）解析所用的本地包根目录候选（应用启动时注入） */
    @Volatile
    private var relativeRoots: List<File> = emptyList()

    /** 注入本地包根目录候选（按序探测，首个命中生效；AppContainer 初始化时调用） */
    fun setRelativeRoots(vararg dirs: File?) {
        relativeRoots = dirs.filterNotNull().map { it.absoluteFile }
    }

    /** 判定是否本地文件地址：clan://、file:// 或文件系统绝对路径（盘符/UNC/根） */
    fun isLocalPath(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        return value.startsWith(PREFIX, ignoreCase = true) ||
            value.startsWith("file:", ignoreCase = true) ||
            (value.length > 2 && value[0].isLetter() && value[1] == ':') ||
            value.startsWith("\\\\") || value.startsWith("/") || value.startsWith("\\")
    }

    /** 判定是否包内相对引用（./ ../ 或裸相对如 lib/x.json；http/clan/盘符路径均非相对） */
    fun isRelativeRef(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("http", ignoreCase = true) || value.contains("://")) {
            return false
        }
        return value.startsWith("./") || value.startsWith("../") ||
            value.startsWith(".\\") || value.startsWith("..\\") ||
            (!isLocalPath(value) && !value.contains(":") && value.contains('/'))
    }

    /**
     * 相对引用按基准目录解析为 clan://localhost/<绝对路径>；
     * 已是绝对形态（http/clan/file/盘符）原样返回。
     */
    fun resolveClan(baseDir: File, raw: String): String {
        val value = raw.trim()
        if (!isRelativeRef(value)) return value
        val resolved = File(baseDir, value).canonicalFile
        return PREFIX + resolved.absolutePath.replace('\\', '/')
    }

    /**
     * TVBox 生态本地形态统一归一为 clan://localhost/ 稳定入库形态（仅本地包导入时调用）：
     * - http://127.0.0.1:9978/file/<存储根相对路径> → clan://localhost/<相对路径>
     * - file://<相对路径> → clan://localhost/<相对路径>（file:/// 绝对形态 → clan 绝对）
     * - clan://localhost/<相对路径> → 原样（运行时按根目录候选解析）
     * http 等远程地址原样返回。
     */
    fun normalizeTvBoxLocal(raw: String): String {
        val value = raw.trim()
        TVBOX_SERVER_FILE.find(value)?.let { match ->
            return value.replaceRange(match.range, PREFIX + match.groupValues[1])
        }
        if (value.startsWith("file:", ignoreCase = true)) {
            val rest = value.substring(5)
            return when {
                rest.startsWith("///") -> PREFIX + cleanDriveSlash(rest.drop(2))
                rest.startsWith("//localhost/", ignoreCase = true) ->
                    PREFIX + rest.substring("//localhost/".length)
                rest.startsWith("//") -> PREFIX + rest.drop(2)
                else -> PREFIX + rest.trimStart('/')
            }
        }
        return value
    }

    /** clan/file/绝对路径 → 本地 File；http 等远程地址返回 null。相对形态按根目录候选解析 */
    fun toFile(raw: String): File? {
        val normalized = normalizeTvBoxLocal(raw.trim())
        if (!normalized.startsWith(PREFIX, ignoreCase = true)) {
            if (!isLocalPath(normalized)) return null
            return File(normalized)
        }
        val rest = normalized.substring(PREFIX.length)
        val file = if (isAbsolutePath(rest)) File(cleanDriveSlash(rest)) else resolveRelative(rest) ?: return null
        return file
    }

    /**
     * clan 地址 → 本地代理文件 http 地址（路径式 /file/<编码绝对路径>）。
     * 路径式保证 quickjs 等引擎的相对 import（./xxx.js）按 URL 规范解析时
     * 仍落在本路由下（查询串形态会被相对解析丢弃导致 404）。
     */
    fun toProxyHttp(clanUrl: String, port: Int): String {
        val absolute = toFile(clanUrl)?.absolutePath ?: clanUrl.trim().substring(PREFIX.length)
        val encoded = absolute.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            .joinToString("/") { segment -> URLEncoder.encode(segment, "UTF-8").replace("+", "%20") }
        return "http://127.0.0.1:$port/file/$encoded"
    }

    /** 盘符路径容忍 /C:/ 形态（URI 风格根斜杠前缀） */
    private fun cleanDriveSlash(path: String): String =
        if (path.length > 2 && path[0] == '/' && path[1].isLetter() && path[2] == ':') path.substring(1) else path

    private fun isAbsolutePath(path: String): Boolean =
        isLocalPath(path) && !path.startsWith(PREFIX, ignoreCase = true)

    /** 存储根相对路径按候选根目录解析（首个命中生效；未命中返回首个候选拼接以便报错明确） */
    private fun resolveRelative(rel: String): File? {
        val roots = relativeRoots
        if (roots.isEmpty()) return null
        val hit = roots.firstOrNull { root -> File(root, rel).isFile || File(root, rel).isDirectory }
            ?: return File(roots.first(), rel)
        return File(hit, rel)
    }
}
