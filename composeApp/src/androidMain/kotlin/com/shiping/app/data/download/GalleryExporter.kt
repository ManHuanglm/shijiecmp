package com.shiping.app.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.shiping.app.data.spider.SpiderHttp
import com.shiping.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 视频导出到系统相册（Movies/视界）：
 * - mp4 直链：直接 HTTP 下载写入 MediaStore
 * - m3u8 等自适应流：用 Media3 Transformer 转封装为 MP4（H.264/AAC 不重编码，无损）后写入相册
 *
 * 输出为标准 MP4 文件，相册/文件管理器可见，App 内也可通过返回的 Uri 离线播放。
 */
object GalleryExporter {

    private const val TAG = "GalleryExporter"
    private const val ALBUM_DIR = "视界"
    private const val MIME_MP4 = "video/mp4"
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** URL 去掉查询参数后是否为 .mp4 直链 */
    fun isDirectMp4(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".mp4", ignoreCase = true)
    }

    /** 导出结果：相册地址 + 文件字节数 */
    data class ExportResult(val localPath: String, val sizeBytes: Long)

    /**
     * 导出视频到相册。
     * @return [ExportResult] 相册中的本地地址（MediaStore content Uri 或文件路径）+ 文件大小
     * @param headers 播放请求头（UA/Referer 防盗链），直链下载时注入
     * @param onProgress 进度回调 0-100
     * @param onBytes 已下载字节快照回调（仅直链路径有值；HLS 转封装无字节语义）
     */
    @UnstableApi
    suspend fun exportToGallery(
        context: Context,
        url: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Int) -> Unit = {},
        onBytes: (bytesDone: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val baseName = sanitizeFileName(title.ifBlank { "video_${System.currentTimeMillis()}" })

        if (isDirectMp4(url)) {
            val tempFile = File(appContext.cacheDir, "dl_${System.nanoTime()}.mp4")
            try {
                downloadDirect(url, headers, tempFile, onProgress, onBytes)
                onProgress(99)
                val path = saveToGallery(appContext, tempFile, baseName)
                ExportResult(path, tempFile.length())
            } finally {
                tempFile.delete()
            }
        } else {
            // HLS/其他流：Transformer 转封装为 MP4
            val tempFile = File(appContext.cacheDir, "export_${System.nanoTime()}.mp4")
            try {
                transformToMp4(appContext, url, tempFile, onProgress)
                onProgress(99)
                val size = tempFile.length()
                val path = saveToGallery(appContext, tempFile, baseName)
                ExportResult(path, size)
            } finally {
                tempFile.delete()
            }
        }
    }

    /** 直链下载到临时文件（带进度与字节快照） */
    private fun downloadDirect(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (Int) -> Unit,
        onBytes: (Long, Long) -> Unit,
    ) {
        val builder = okhttp3.Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        SpiderHttp.client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IllegalStateException("下载失败：响应为空")
            val total = body.contentLength()
            var written = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            onProgress(((written * 100) / total).toInt().coerceIn(0, 98))
                        }
                        onBytes(written, total)
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * 用 Transformer 将网络流（HLS 等）转封装为本地 MP4。
     * start/cancel/getProgress 需在带 Looper 的应用线程调用，这里切到主线程；进度轮询与完成等待并发进行。
     */
    @UnstableApi
    private suspend fun transformToMp4(
        context: Context,
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        val progressHolder = ProgressHolder()
        // 使用 Media3 内置纯 Java MP4 封装器：平台 MPEG4Writer 在部分设备/模拟器上
        // 遇到异常 NAL 时会直接 FORTIFY abort 杀进程（无法捕获），内置封装器只会抛出可捕获异常
        val transformer = Transformer.Builder(context)
            .setMuxerFactory(InAppMuxer.Factory.Builder().build())
            .build()
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(url)).build()

        // 进度轮询：Transformer 无独立进度回调，在应用线程周期性 getProgress
        val pollJob = launch {
            while (isActive) {
                runCatching {
                    if (transformer.getProgress(progressHolder) ==
                        Transformer.PROGRESS_STATE_AVAILABLE
                    ) {
                        onProgress((progressHolder.progress * 100).toInt().coerceIn(0, 98))
                    }
                }
                kotlinx.coroutines.delay(500)
            }
        }

        try {
            suspendCancellableCoroutine<Unit> { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                    ) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                        exportException: androidx.media3.transformer.ExportException,
                    ) {
                        AppLog.e(
                            TAG,
                            "Transformer 错误 ${exportException.errorCodeName} " +
                                "msg=${exportException.message} cause=${exportException.cause}",
                            exportException,
                        )
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                runCatching { transformer.start(editedItem, target.absolutePath) }
                    .onFailure { if (cont.isActive) cont.resumeWithException(it) }
            }
        } finally {
            pollJob.cancel()
        }
    }

    /**
     * 将 MP4 文件写入系统相册。
     * Android 10+ 走 MediaStore（无需权限）；低版本写公共 Movies 目录并扫描。
     * @return content Uri 字符串 或 文件绝对路径
     */
    private fun saveToGallery(context: Context, file: File, baseName: String): String {
        val fileName = "$baseName.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_MP4)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + File.separator + ALBUM_DIR,
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("无法创建相册文件")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, COPY_BUFFER_BYTES) }
            } ?: throw IllegalStateException("无法写入相册文件")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                ALBUM_DIR,
            )
            if (!dir.exists()) dir.mkdirs()
            var target = File(dir, fileName)
            var suffix = 1
            while (target.exists()) {
                target = File(dir, "$baseName($suffix).mp4")
                suffix++
            }
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(MIME_MP4),
                null,
            )
            target.absolutePath
        }
    }

    /** 删除相册中的导出文件（删除下载任务时调用） */
    fun deleteFromGallery(context: Context, localPath: String) {
        if (localPath.isBlank()) return
        runCatching {
            if (localPath.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(localPath), null, null)
            } else {
                File(localPath).delete()
            }
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "video" }
}
