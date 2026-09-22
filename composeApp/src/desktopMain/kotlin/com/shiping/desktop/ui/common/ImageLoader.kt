package com.shiping.desktop.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 桌面位图加载 actual：OkHttp 拉取 + Skia 解码（UA/Referer 注入防盗链） */
actual suspend fun fetchImageBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/114.0.0.0 Safari/537.36")
            .header("Referer", refererOf(url))
            .build()
        SpiderHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.w("ImageLoader", "加载失败 code=${response.code} $url")
                return@use null
            }
            val bytes = response.body?.bytes()
            if (bytes == null) {
                AppLog.w("ImageLoader", "空响应 $url")
                return@use null
            }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }
    }.onFailure { AppLog.w("ImageLoader", "加载异常: ${it.message} $url") }
        .getOrNull()
}

/** 清空内存图片缓存（cmpMain 统一缓存，桌面无额外平台缓存） */
actual fun clearImageCache() = Unit

/** 图 URL 的 origin（scheme://host），作为 Referer 兜底部分图床的同域防盗链 */
private fun refererOf(url: String): String = url.split("/").take(3).joinToString("/")
