package com.shiping.app.data.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.shiping.app.util.AppLog
import com.shiping.shared.player.HlsAdFilter as SharedHlsAdFilter
import java.io.ByteArrayOutputStream

/**
 * m3u8 播放列表去广告过滤器：核心规则实现于 shared jvmCommon
 * （与桌面本地代理 /m3u8 路由共用同一份规则），本 object 仅作 Android 侧入口委托。
 */
object HlsAdFilter {

    fun filter(playlist: String): SharedHlsAdFilter.Result = SharedHlsAdFilter.filter(playlist)
}

/**
 * 拦截 m3u8 播放列表请求并重写响应体的 DataSource（迁移自源项目方案）。
 * 播放列表通过包装的数据源整体读入 → [HlsAdFilter] 过滤 → 以新长度返回；
 * 切片请求原样透传（切片 URL 未变，相对路径仍按原始播放列表地址解析）。
 */
@UnstableApi
class AdFilterDataSource(private val upstream: DataSource) : DataSource {

    private var delegate: DataSource? = null

    /** 非空表示当前响应是已重写的播放列表 */
    private var playlistBytes: ByteArray? = null
    private var readPosition = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // 播放列表命中：path 后缀 .m3u8，或经本地代理转发（/hdr、/proxy）query 携带
        // .m3u8 目标地址（切片 URL query 带 .m3u8 极罕见，误判仅多一次整体读入，
        // HlsAdFilter 对非播放列表内容原样返回，无副作用）
        val isPlaylist = dataSpec.uri.toString()
            .substringBefore("#").lowercase()
            .contains(".m3u8")

        delegate = upstream
        if (!isPlaylist) {
            return upstream.open(dataSpec)
        }

        // 整体读入播放列表原文
        val length = upstream.open(dataSpec)
        val out = ByteArrayOutputStream(if (length > 0) length.toInt() else 8 * 1024)
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = upstream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }

        val result = HlsAdFilter.filter(out.toString("UTF-8"))
        AppLog.i("HlsAdFilter", "播放列表 ${dataSpec.uri} → 移除 ${result.removedSegments}")
        val bytes = result.text.toByteArray(Charsets.UTF_8)
        playlistBytes = bytes
        readPosition = 0
        return bytes.size.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val bytes = playlistBytes
        if (bytes != null) {
            if (readPosition >= bytes.size) return C.RESULT_END_OF_INPUT
            val size = minOf(length, bytes.size - readPosition)
            System.arraycopy(bytes, readPosition, target, offset, size)
            readPosition += size
            return size
        }
        return delegate?.read(target, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            playlistBytes = null
            readPosition = 0
        }
    }
}

/** [AdFilterDataSource] 的工厂，包装现有数据源工厂（如 HTTP 数据源） */
@UnstableApi
class AdFilterDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        AdFilterDataSource(upstreamFactory.createDataSource())
}
