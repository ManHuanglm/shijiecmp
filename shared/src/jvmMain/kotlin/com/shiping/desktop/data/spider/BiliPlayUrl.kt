package com.shiping.desktop.data.spider

import com.google.gson.JsonParser
import com.shiping.desktop.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * B 站直链辅助：清晰度切换时用 cid+aid+qn 重新请求 playurl 接口换取新直链。
 * （B 站 mp4 直链为单码率文件，切换清晰度必须换链接，对齐 app PlayerViewModel 实现）
 */
object BiliPlayUrl {

    /** 请求 B 站 playurl 接口换取指定 qn 的新直链（data.durl[0].url），失败返回 null */
    suspend fun fetch(aid: Long, cid: Long, qn: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(buildApi(aid, cid, qn))
                .header("User-Agent", Constants.API_USER_AGENT)
                .header("Referer", Constants.BILI_REFERER)
                .build()
            SpiderHttp.client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                JsonParser.parseString(body)
                    .takeIf { it.isJsonObject }?.asJsonObject
                    ?.getAsJsonObject("data")
                    ?.getAsJsonArray("durl")
                    ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** 拼接 B 站 playurl 接口地址（html5 平台，high_quality 允许取高码率） */
    private fun buildApi(aid: Long, cid: Long, qn: Int): String =
        "${Constants.BILI_PLAYURL_API}?avid=$aid&cid=$cid&qn=$qn&type=&otype=json" +
            "&platform=html5&high_quality=1"
}
