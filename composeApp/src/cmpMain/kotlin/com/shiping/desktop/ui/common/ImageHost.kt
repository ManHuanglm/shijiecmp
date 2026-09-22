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
import androidx.compose.ui.layout.ContentScale

/**
 * 跨端图片加载：内存 LRU 缓存（cmpMain 统一管理）+ 平台位图解码
 * （expect/actual：桌面=Skia+SpiderHttp；Android=okhttp+BitmapFactory）。
 * 图片请求统一携带浏览器 UA（图床普遍校验 UA），Referer 取 origin 兜底防盗链。
 */
object ImageCache {

    private const val MAX_CACHE = 256

    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_CACHE
    }

    fun cached(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    fun put(url: String, bitmap: ImageBitmap) = synchronized(cache) { cache[url] = bitmap }

    /** 清空内存图片缓存（设置页清理数据用） */
    fun clear() = synchronized(cache) { cache.clear() }
}

/** 平台位图网络加载（IO 线程调用；UA/Referer 由实现注入），失败返回 null */
expect suspend fun fetchImageBitmap(url: String): ImageBitmap?

/** 清空平台侧图片缓存（cmpMain 统一缓存由 [ImageCache.clear] 负责） */
expect fun clearImageCache()

/** 异步网络图片：加载中显示占位背景，失败显示标题首字 */
@Composable
fun NetImage(
    url: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = ImageCache.cached(url), key1 = url) {
        if (url.isBlank()) return@produceState
        ImageCache.cached(url)?.let { value = it }
        fetchImageBitmap(url)?.let { value = it.also { bmp -> ImageCache.put(url, bmp) } }
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
