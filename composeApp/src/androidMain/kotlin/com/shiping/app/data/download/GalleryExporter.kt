package com.shiping.app.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 相册写入器：将下载完成的临时产物登记进系统相册（Movies/视界）。
 * 下载逻辑（直链 Range 续传 / HLS 分片并发）在 [DownloadEngine] 中实现，
 * 本类只负责「临时文件 → 相册 content Uri」的落库与删除。
 */
object GalleryExporter {

    private const val ALBUM_DIR = "视界"
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** 按扩展名推断相册登记 MIME */
    internal fun mimeFor(ext: String): String = when (ext) {
        "ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        else -> "video/mp4"
    }

    /** 文件名清洗（非法字符替换 + 长度截断） */
    internal fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "video" }

    /**
     * 将临时产物写入系统相册。
     * Android 10+ 走 MediaStore（无需权限）；低版本写公共 Movies 目录并扫描。
     * @param ext 产物扩展名（决定文件名与 MIME，如 mp4/ts）
     * @return content Uri 字符串 或 文件绝对路径
     */
    internal fun saveToGallery(context: Context, file: File, baseName: String, ext: String): String {
        val fileName = "$baseName.$ext"
        val mime = mimeFor(ext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
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
                target = File(dir, "$baseName($suffix).$ext")
                suffix++
            }
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(mime),
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
}
