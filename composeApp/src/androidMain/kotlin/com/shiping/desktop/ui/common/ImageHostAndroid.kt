package com.shiping.desktop.ui.common

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Android 位图加载 actual：OkHttp 拉取（浏览器 UA/Referer 防盗链）+ BitmapFactory 解码 */
actual suspend fun fetchImageBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
            .header("Referer", refererOf(url))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
                ?.takeIf { it.isNotEmpty() }
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }.getOrNull()
}

/** 清空内存图片缓存（cmpMain 统一缓存，Android 无额外平台缓存） */
actual fun clearImageCache() = Unit

/** 图 URL 的 origin（scheme://host），作为 Referer 兜底部分图床的同域防盗链 */
private fun refererOf(url: String): String = url.split("/").take(3).joinToString("/")
