/*
 * Crawler compat empty stubs (auto generated):
 * guarantee class resolution for crawler jars; add members on demand.
 */
package android.net

open class UrlQuerySanitizer {

    /** 查询参数键值对（pg Config 等爬虫解析 URL 参数用） */
    class ParameterValuePair(val parameter: String?, val value: String?)

    fun parseUrl(url: String?) = Unit

    fun getValueFirst(parameter: String?): String? = null

    fun getParameterList(): Array<ParameterValuePair> = emptyArray()

    fun setAllowUnregisteredParamaters(allow: Boolean) = Unit

    fun setUnregisteredParameterValueSanitizer(sanitizer: java.text.FieldPosition?) = Unit
}
