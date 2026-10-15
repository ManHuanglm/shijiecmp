package android.net

import java.net.URI
import java.net.URLDecoder

/**
 * 桌面 stub：android.net.Uri 基于 java.net.URI 的最小实现。
 * parse 永不抛异常（非法输入退化为空字段，与 Android 行为一致）。
 */
class Uri private constructor(private val raw: String) {

    private val uri: URI? = runCatching { URI(raw) }.getOrNull()

    fun getScheme(): String? = uri?.scheme?.lowercase()

    fun getHost(): String? = runCatching { uri?.host }.getOrNull()

    fun getPort(): Int = uri?.port ?: -1

    fun getPath(): String? = uri?.rawPath

    /** 取路径最后一段（URL 解码；无路径返回 null，与 Android 一致） */
    fun getLastPathSegment(): String? {
        val path = uri?.rawPath ?: return null
        if (path.isEmpty()) return null
        return decodeValue(path.substringAfterLast('/'))
    }

    fun getQuery(): String? = uri?.rawQuery

    fun getUserInfo(): String? = uri?.rawUserInfo?.let { decodeValue(it) }

    fun getFragment(): String? = uri?.rawFragment

    fun isAbsolute(): Boolean = uri?.isAbsolute ?: false

    fun isOpaque(): Boolean = uri?.isOpaque ?: false

    fun toString_(): String = raw

    override fun toString(): String = raw

    /** 取查询串中指定 key 的首个值（URL 解码，key 不存在返回 null，与 Android 一致） */
    fun getQueryParameter(key: String): String? {
        val query = uri?.rawQuery ?: return null
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = if (eq >= 0) pair.substring(0, eq) else pair
            if (decode(name) == key) {
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                return decode(value)
            }
        }
        return null
    }

    /** 取全部查询参数 */
    fun getQueryParameters(): List<String> {
        val query = uri?.rawQuery ?: return emptyList()
        return query.split("&").filter { it.isNotEmpty() }.map { decodeValue(it) }
    }

    private fun decodeValue(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    companion object {

        const val EMPTY = ""

        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic
        fun encode(s: String?): String? = java.net.URLEncoder.encode(s.orEmpty(), "UTF-8")

        @JvmStatic
        fun decode(s: String?): String? =
            s?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

        @JvmStatic
        fun empty(): Uri = Uri("")
    }

    /** 桌面 stub：链式构建器（拼字符串，够 catvod 爬虫的常规构造即可） */
    class Builder {

        private val parts = StringBuilder()

        fun scheme(value: String?): Builder = apply { if (value != null) parts.insert(0, "$value:") }

        fun encodedAuthority(value: String?): Builder = apply { if (value != null) append("//", value) }

        fun authority(value: String?): Builder = encodedAuthority(value)

        fun path(value: String?): Builder = apply { if (value != null) append("/", value) }

        fun appendPath(value: String?): Builder = apply { if (value != null) append("/", value) }

        fun appendQueryParameter(key: String?, value: String?): Builder = apply {
            if (key != null && value != null) {
                val sep = if (parts.indexOf("?") >= 0) "&" else "?"
                append(sep, "${encode(key)}=${encode(value)}")
            }
        }

        fun encodedQuery(value: String?): Builder = apply { if (value != null) append("?", value) }

        fun fragment(value: String?): Builder = apply { if (value != null) append("#", value) }

        fun build(): Uri = Uri(parts.toString())

        private fun append(separator: String, value: String) {
            if (parts.isNotEmpty() && parts.last() == '/') {
                if (value.startsWith("/")) parts.setLength(parts.length - 1)
            } else if (parts.isNotEmpty() && separator == "/" && !parts.endsWith(":") && !value.startsWith("/")) {
                parts.append(separator)
            } else if (separator != "/") {
                parts.append(separator)
            }
            parts.append(value)
        }
    }
}
