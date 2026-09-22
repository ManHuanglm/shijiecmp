package com.shiping.desktop.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.shiping.desktop.data.model.ApiResponse
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * MacCMS 视频接口客户端（OkHttp + Gson，对齐 app ApiService 语义）：
 * 列表 {base}?ac=list&pg=N&t=T&wd=K，详情 {base}?ac=detail&ids=1,2。
 * 请求带浏览器 UA（部分资源站校验 UA）。
 */
class CmsClient {

    private val gson = Gson()

    /** 获取首页/分类/搜索视频列表 */
    suspend fun getVodList(baseUrl: String, page: Int = 1, typeId: Int = 0, keyword: String? = null): ApiResponse<Vod> =
        request(buildUrl(baseUrl, mapOf("ac" to "list", "pg" to page.toString(), "t" to typeId.toString()) +
            (keyword?.takeIf { it.isNotBlank() }?.let { mapOf("wd" to it) } ?: emptyMap())))

    /** 获取视频详情（ids 逗号分隔） */
    suspend fun getVodDetail(baseUrl: String, ids: String): ApiResponse<Vod> =
        request(buildUrl(baseUrl, mapOf("ac" to "detail", "ids" to ids)))

    /** 拼接接口地址：容忍 base 以 /、?、& 结尾的各类写法 */
    private fun buildUrl(baseUrl: String, params: Map<String, String>): String {
        val base = baseUrl.trim()
        val separator = when {
            base.contains("?") -> if (base.endsWith("?") || base.endsWith("&")) "" else "&"
            base.endsWith("/") -> "?"
            else -> "?"
        }
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        return base + separator + query
    }

    private suspend fun request(url: String): ApiResponse<Vod> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Constants.API_USER_AGENT)
            .build()
        SpiderHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("CMS 接口 HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IllegalStateException("CMS 接口返回为空")
            gson.fromJson(body, ArrayResponse::class.java).toTyped()
        }
    }

    /** Gson 反序列化中转（避免 TypeToken 泛型擦除问题） */
    private class ArrayResponse(
        @SerializedName("code") val code: Int = 0,
        @SerializedName("msg") val msg: String = "",
        @SerializedName("page") val page: Int = 1,
        @SerializedName("pagecount") val pageCount: Int = 0,
        @SerializedName("limit") val limit: Int = 20,
        @SerializedName("total") val total: Int = 0,
        @SerializedName("list") val list: List<Vod> = emptyList(),
        @SerializedName("class") val classList: List<com.shiping.desktop.data.model.Category> = emptyList(),
    ) {
        fun toTyped(): ApiResponse<Vod> = ApiResponse(
            code = code, msg = msg, page = page, pageCount = pageCount,
            limit = limit, total = total, list = list, classList = classList,
        )
    }
}
