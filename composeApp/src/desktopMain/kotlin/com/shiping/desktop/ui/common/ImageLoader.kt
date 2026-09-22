package com.shiping.desktop.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量图片加载器：OkHttp 拉取 + 内存位图缓存（桌面单用户场景够用）。
 * 图片请求带浏览器 UA（图床普遍校验 UA）。
 */
object ImageLoader {

    private const val TAG = "ImageLoader"

    private const val MAX_CACHE = 256

    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_CACHE
    }

    /** 同步取缓存 */
    fun cached(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    /** 清空内存图片缓存（设置页清理数据用） */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /** 网络加载（IO 线程），失败返回 null 并写日志（图床防盗链/编码失败可追溯） */
    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/114.0.0.0 Safari/537.36")
                .header("Referer", refererOf(url))
                .build()
            SpiderHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "加载失败 code=${response.code} $url")
                    return@use null
                }
                val bytes = response.body?.bytes()
                if (bytes == null) {
                    AppLog.w(TAG, "空响应 $url")
                    return@use null
                }
                val bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                synchronized(cache) { cache[url] = bitmap }
                bitmap
            }
        }.onFailure { AppLog.w(TAG, "加载异常: ${it.message} $url") }
            .getOrNull()
    }

    /** 图 URL 的 origin（scheme://host），作为 Referer 兜底部分图床的同域防盗链 */
    private fun refererOf(url: String): String = url.split("/").take(3).joinToString("/")
}

/** 异步网络图片：加载中显示占位背景，失败显示标题首字 */
@Composable
fun NetImage(
    url: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = ImageLoader.cached(url), key1 = url) {
        if (url.isBlank()) return@produceState
        value = ImageLoader.load(url)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(bitmap = current, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        } else {
            Text(
                text = fallbackText.trim().take(2).ifBlank { "视" },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
