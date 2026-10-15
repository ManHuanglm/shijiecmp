package com.shiping.app.data.download

import android.os.SystemClock
import com.shiping.app.ShipingApp
import com.shiping.app.ShipingGraph
import com.shiping.app.data.local.AppDatabase
import com.shiping.app.data.model.DownloadEntity
import com.shiping.app.data.spider.SpiderHttp
import com.shiping.app.util.AppLog
import com.shiping.shared.download.DownloadStatus
import com.shiping.shared.download.SpeedLimiter
import com.shiping.shared.player.HlsAdFilter
import com.shiping.shared.player.HlsAesDecryptor
import com.shiping.shared.prefs.PrefsKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * 下载引擎：Room downloads 表驱动的并发队列（与桌面 DesktopDownloadEngine 架构对齐）。
 * - 并发任务数可配置（KEY_DOWNLOAD_MAX_PARALLEL 1-3）；HLS 分片并发下载可配置（1-6）
 * - mp4 等直链经 SpiderHttp 下载（Range 断点续传 + 首块嗅探防假文件）；
 *   m3u8 拉清单（自动去广告过滤 + 分片地址绝对化）后分片并发下载拼接为单文件
 *   （分片失败重试 + 分片级断点续传，.part 保留至完成/重启）
 * - 产物写临时文件后经 [GalleryExporter.saveToGallery] 登记进相册，完成态 localPath=相册地址
 * - 暂停/删除取消进行中任务；启动时残留「下载中」置失败并清理 .part
 * - 限速/并发等档位每次任务现读偏好；入队检查磁盘最低预留 1GB
 */
object DownloadEngine {

    private const val TAG = "DownloadEngine"

    /** 进度落库节流：进度百分比步进 ≥1 才写（一次任务至多 100 次） */
    private const val PROGRESS_MIN_STEP = 1

    /** 速度采样间隔（毫秒） */
    private const val SPEED_TICK_MS = 800L

    /** 拷贝缓冲区字节数 */
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** 直链首块嗅探字节数（判定 m3u8 文本/网页错误页足够） */
    private const val PROBE_BYTES = 512

    /** 本地代理地址前缀（识别包装地址） */
    private const val LOCAL_PREFIX = "http://127.0.0.1:"

    /** 磁盘最低预留空间（剩余低于此值拒绝新任务） */
    private const val MIN_FREE_DISK_BYTES = 1024L * 1024 * 1024

    /** 分片下载失败重试次数（不含首次） */
    private const val SEGMENT_RETRY = 2

    /** 分片重试间隔（毫秒） */
    private const val SEGMENT_RETRY_DELAY_MS = 800L

    /** 下载中临时文件后缀 */
    private const val PART_SUFFIX = ".part"

    /** 清单层级上限（master → 媒体） */
    private const val PLAYLIST_MAX_DEPTH = 2

    /** 属性行 URI 引用提取/改写（#EXT-X-KEY / #EXT-X-MAP 的 URI="..."） */
    private val QUOTED_URI_REGEX = Regex("URI=\"([^\"]+)\"")

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dao get() = AppDatabase.getInstance(ShipingApp.context).downloadDao()

    /** 下载工作目录（外部专属存储 downloads）：.part 断点文件与完成态产物（卸载才清理） */
    private val workDir: File by lazy {
        val external = ShipingApp.context.getExternalFilesDir(null)
        File(external ?: ShipingApp.context.filesDir, "downloads").apply { mkdirs() }
    }

    /** 任务领取互斥：保证「查询排队 + 置下载中」对多 worker 原子（防重复领取） */
    private val pickMutex = kotlinx.coroutines.sync.Mutex()

    /** 队列 worker 集合（数量补足至并发任务数档位） */
    private val workers = mutableListOf<Job>()

    /** 进行中任务 → 所属 worker（暂停/删除取消用） */
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** 启动初始化：残留「下载中」置失败（.part 保留，重试即断点续传）+ 自动恢复队列 */
    fun init() {
        ioScope.launch {
            runCatching {
                dao.resetStatuses(
                    listOf(DownloadEntity.STATUS_DOWNLOADING),
                    DownloadEntity.STATUS_FAILED,
                    "下载被中断（应用退出），点重试从断点续传",
                )
            }.onFailure { AppLog.w(TAG, "中断任务清理失败: ${it.message}") }
            kick()
        }
    }

    /**
     * 入队下载任务（同任务已完成/排队中则拒绝，暂停/失败则重置重下）。
     * @return 用户提示文案
     */
    suspend fun enqueue(entity: DownloadEntity): String = withContext(Dispatchers.IO) {
        when {
            workDir.usableSpace < MIN_FREE_DISK_BYTES -> "存储剩余空间不足 1GB，无法下载"
            else -> {
                val existing = dao.getById(entity.contentId)
                when {
                    existing != null && existing.status == DownloadEntity.STATUS_COMPLETED -> "该集已在下载列表中"
                    existing != null && existing.status in intArrayOf(
                        DownloadEntity.STATUS_DOWNLOADING,
                        DownloadEntity.STATUS_QUEUED,
                    ) -> "该集正在下载中"
                    else -> {
                        dao.insert(entity)
                        kick()
                        "已加入下载队列"
                    }
                }
            }
        }
    }

    /** 暂停任务：进行中取消当前下载（HLS 保留 .part 与分片计数，恢复续传），排队中仅改状态 */
    fun pause(contentId: String) {
        ioScope.launch {
            activeJobs[contentId]?.cancel()
            runCatching { dao.updateStatus(contentId, DownloadEntity.STATUS_PAUSED) }
        }
    }

    /** 恢复/重试任务：重新排队（HLS 从已完成分片续传）并唤醒队列 */
    fun resume(contentId: String) {
        ioScope.launch {
            runCatching { dao.updateStatus(contentId, DownloadEntity.STATUS_QUEUED) }
            kick()
        }
    }

    /** 删除任务：取消进行中下载 + 删除相册已导出文件 + 删库记录 */
    fun delete(contentId: String) {
        ioScope.launch {
            activeJobs[contentId]?.cancel()
            runCatching {
                dao.getById(contentId)?.localPath?.takeIf { it.isNotBlank() }?.let { localPath ->
                    GalleryExporter.deleteFromGallery(ShipingApp.context, localPath)
                }
            }.onFailure { AppLog.w(TAG, "相册文件删除失败: ${it.message}") }
            runCatching {
                dao.getById(contentId)?.let { task ->
                    File(targetPathFor(task) + PART_SUFFIX).delete()
                }
            }
            runCatching { dao.delete(contentId) }
        }
    }

    // region 队列执行

    /** 下载设置变化（并发档位/限速/仅 WiFi）后唤醒队列重新评估 */
    fun onSettingsChanged() = kick()

    /** 唤醒队列（幂等：worker 数不足并发任务档位时补足；设置变化后同样经此生效） */
    private fun kick() {
        synchronized(this) {
            workers.removeAll { !it.isActive }
            while (workers.size < maxParallel()) {
                workers += ioScope.launch { runQueue() }
            }
        }
    }

    /** 并发任务数档位（现读偏好，1-3） */
    private fun maxParallel(): Int = ShipingGraph.prefs.getInt(PrefsKeys.KEY_DOWNLOAD_MAX_PARALLEL, 1).coerceIn(1, 3)

    /** 同步取下一个排队任务并置下载中（Mutex 保证「查询+置状态」原子，防多 worker 重复领取） */
    private suspend fun pickNextQueued(): DownloadEntity? = pickMutex.withLock {
        val task = dao.getAll().firstOrNull { it.status == DownloadEntity.STATUS_QUEUED } ?: return null
        dao.updateStatus(task.contentId, DownloadEntity.STATUS_DOWNLOADING)
        task
    }

    private suspend fun runQueue() {
        while (true) {
            // 仅 WiFi 约束：蜂窝网络下本轮不取任务（关闭开关/恢复后经 resume、入队或设置变化唤醒）
            if (ShipingGraph.prefs.getBoolean(PrefsKeys.KEY_DOWNLOAD_WIFI_ONLY, false) && isCellularNetwork()) break
            val task = pickNextQueued() ?: break
            activeJobs[task.contentId] = coroutineContext[Job]!!
            try {
                performDownload(task)
            } catch (e: CancellationException) {
                // 暂停/删除取消：置为暂停态（NonCancellable 保证取消上下文中落库成功）
                withContext(NonCancellable) {
                    runCatching { dao.updateStatus(task.contentId, DownloadEntity.STATUS_PAUSED) }
                }
                throw e
            } catch (t: Throwable) {
                AppLog.e(TAG, "下载失败 ${task.title}: ${t.message}", t)
                runCatching {
                    dao.updateFailed(task.contentId, DownloadEntity.STATUS_FAILED, t.message ?: "未知错误")
                }
            } finally {
                activeJobs.remove(task.contentId)
            }
        }
    }

    /** 下载单任务：按地址类型分流（每次现读限速档位；目标路径首次分配后落库，续传稳定复用防重名漂移） */
    private suspend fun performDownload(task: DownloadEntity) {
        if (isUnsupportedDownloadUrl(task.url)) {
            throw IllegalStateException("该播放地址（DASH/代理流）暂不支持下载")
        }
        val headers = parseHeaders(task.headersJson)
        val limiter = SpeedLimiter(ShipingGraph.prefs.getInt(PrefsKeys.KEY_DOWNLOAD_SPEED_LIMIT_KB, 0).toLong() * 1024L)
        val targetPath = task.localPath.takeIf {
            it.isNotBlank() && it.startsWith(workDir.absolutePath)
        } ?: targetPathFor(task).also { path ->
            runCatching { dao.updateLocalPath(task.contentId, path) }
        }
        if (isHlsUrl(task.url)) {
            performHls(task, targetPath, headers, limiter)
        } else {
            performDirect(task, targetPath, headers, limiter)
        }
    }

    /**
     * 直链下载：临时 .part 节流写入 → 完成后登记相册。
     * 首块内容嗅探：响应体实为 m3u8 文本（无后缀 HLS 端点/伪装直链）时转 HLS 分片下载，
     * 网页错误页直接判失败——避免把 2KB 文本当视频落盘成假「已下载」。
     * 断点续传：.part 存在时带 Range 头续传（服务器 206 才续，200 截断重来）。
     */
    private suspend fun performDirect(
        task: DownloadEntity,
        targetPath: String,
        headers: Map<String, String>,
        limiter: SpeedLimiter,
    ) {
        val target = File(targetPath)
        val part = File(targetPath + PART_SUFFIX)
        val resumeFrom = if (part.exists()) part.length() else 0L
        val writer = ProgressWriter(task.contentId)
        var hlsDetected = false
        try {
            withContext(Dispatchers.IO) {
                val builder = okhttp3.Request.Builder().url(task.url)
                if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")
                headers.forEach { (name, value) -> builder.header(name, value) }
                SpiderHttp.client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("下载失败（HTTP ${response.code}）")
                    }
                    val body = response.body ?: throw IllegalStateException("下载失败：响应为空")
                    // 服务器确认 206 才是真续传；200 表示不支持 Range，从头重写
                    val resuming = resumeFrom > 0 && response.code == 206
                    val input = body.byteStream()
                    // 首块嗅探（手动读满，避免依赖 API 33+ 的 readNBytes）
                    val probe = ByteArray(PROBE_BYTES)
                    var probed = 0
                    while (probed < probe.size) {
                        val read = input.read(probe, probed, probe.size - probed)
                        if (read == -1) break
                        probed += read
                    }
                    if (probed == 0 && !resuming) throw IllegalStateException("下载失败：响应内容为空")
                    val contentType = response.header("Content-Type").orEmpty()
                    val probeLatin = String(probe, 0, probed, Charsets.ISO_8859_1)
                    val probeLower = probeLatin.lowercase()
                    when {
                        contentType.contains("mpegurl", true) || probeLatin.startsWith("#EXTM3U") -> {
                            hlsDetected = true
                            return@withContext
                        }
                        probeLower.contains("<!doctype html") || probeLower.contains("<html") -> {
                            throw IllegalStateException("源返回了网页错误页，无法下载（地址可能已失效）")
                        }
                    }
                    val total = body.contentLength() + if (resuming) resumeFrom else 0L
                    var written = if (resuming) resumeFrom else 0L
                    input.use { stream ->
                        java.io.FileOutputStream(part, resuming).use { output ->
                            if (!resuming) output.write(probe, 0, probed)
                            if (total > 0) {
                                writer.onPercent(((written * 100) / total).toInt().coerceIn(0, 98))
                            }
                            writer.onBytes(written, total)
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = stream.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                written += read
                                limiter.acquire(read)
                                if (total > 0) {
                                    writer.onPercent(((written * 100) / total).toInt().coerceIn(0, 98))
                                }
                                writer.onBytes(written, total)
                            }
                            output.flush()
                        }
                    }
                }
                moveFile(part, target)
            }
            if (hlsDetected) {
                performHls(task, targetPath, headers, limiter)
                return
            }
            completeDownload(task, target)
        } finally {
            // 失败/暂停保留 .part 供断点续传；成功后产物即 target（无临时清理）
            if (hlsDetected && part.exists()) runCatching { part.delete() }
        }
    }

    /**
     * m3u8 下载：拉最终媒体清单（master 跟随首个变体，深度 ≤2；按开关去广告过滤+分片地址绝对化）
     * → 分片并发下载（滑动窗口按序写入）拼接为单文件；
     * 分片失败自动重试；AES-128 加密流自动拉取密钥并解密（SAMPLE-AES 等暂不支持）；
     * 失败/暂停保留 .part 与已完成分片计数，恢复时从断点分片继续追加（分片级续传）。
     */
    private suspend fun performHls(
        task: DownloadEntity,
        targetPath: String,
        headers: Map<String, String>,
        limiter: SpeedLimiter,
    ) {
        val filterEnabled = ShipingGraph.prefs.getBoolean(PrefsKeys.KEY_FILTER_M3U8_AD, true)
        val (playlistUrl, playlist) = fetchMediaPlaylist(task.url, headers, filterEnabled)
        val units = parseDownloadUnits(playlist, playlistUrl)
        if (units.isEmpty()) {
            throw IllegalStateException("播放列表中没有可下载的分片")
        }
        val target = File(targetPath)
        val part = File(targetPath + PART_SUFFIX)
        // 分片级断点续传：.part 存在时从已完成分片继续追加；.part 丢失（重启清理）则从头重来
        var startFrom = task.completedSegments.coerceIn(0, units.size)
        if (startFrom > 0 && !part.exists()) startFrom = 0
        val appending = startFrom > 0
        val writer = ProgressWriter(task.contentId)
        val concurrency = ShipingGraph.prefs.getInt(PrefsKeys.KEY_DOWNLOAD_SEGMENT_CONCURRENCY, 3).coerceIn(1, 6)
        var doneUnits = startFrom
        var bytesDone = if (appending) part.length() else 0L
        withContext(Dispatchers.IO) {
            coroutineScope {
                val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
                java.io.FileOutputStream(part, appending).use { output ->
                    // 滑动窗口：并发预取未来分片，按序 await 写入（保证字节顺序正确）
                    val pending = ArrayDeque<Deferred<ByteArray>>()
                    var nextLaunch = startFrom
                    fun fillWindow() {
                        while (nextLaunch < units.size && pending.size < concurrency) {
                            val unit = units[nextLaunch++]
                            pending.addLast(async { fetchSegmentBytes(unit, headers, limiter, keyCache) })
                        }
                    }
                    fillWindow()
                    while (pending.isNotEmpty()) {
                        coroutineContext.ensureActive()
                        val bytes = pending.removeFirst().await()
                        output.write(bytes)
                        bytesDone += bytes.size
                        doneUnits++
                        runCatching { dao.updateSegmentProgress(task.contentId, doneUnits, bytesDone) }
                        writer.onProgressBytes(bytesDone)
                        writer.onPercent(doneUnits * 98 / units.size)
                        fillWindow()
                    }
                    output.flush()
                }
            }
            moveFile(part, target)
        }
        completeDownload(task, target)
    }

    /** 单个下载分片：init 段无加密信息；媒体分片携带其前最近的 #EXT-X-KEY 与媒体序列号 */
    private data class DownloadUnit(
        val url: String,
        val keyUri: String?,
        val ivHex: String?,
        val sequence: Long,
    )

    /**
     * 清单解析为按序下载单元（init 段 + 媒体分片）：
     * 跟踪 #EXT-X-MEDIA-SEQUENCE 与 #EXT-X-KEY（AES-128 可解密；SAMPLE-AES 等拒下），
     * 媒体分片记录应用其前最近的密钥信息与媒体序列号（无显式 IV 时的解密 IV 来源），
     * 密钥地址与分片地址统一相对 baseUrl 绝对化（幂等，已绝对地址原样保留）。
     */
    private fun parseDownloadUnits(playlist: String, baseUrl: String): List<DownloadUnit> {
        val methodRegex = Regex("METHOD=([A-Z0-9-]+)")
        val ivRegex = Regex("IV=0[xX]([0-9a-fA-F]+)")
        var currentKeyUri: String? = null
        var currentIvHex: String? = null
        var mediaSequence = 0L
        var segmentIndex = 0
        val units = mutableListOf<DownloadUnit>()
        playlist.lineSequence().forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY") -> {
                    val method = methodRegex.find(line)?.groupValues?.get(1) ?: "NONE"
                    when (method) {
                        "NONE" -> {
                            currentKeyUri = null
                            currentIvHex = null
                        }
                        "AES-128" -> {
                            currentKeyUri = QUOTED_URI_REGEX.find(line)?.groupValues?.get(1)
                                ?.let { absolutize(baseUrl, it) }
                                ?: throw IllegalStateException("加密清单缺少密钥地址")
                            currentIvHex = ivRegex.find(line)?.groupValues?.get(1)
                        }
                        else -> throw IllegalStateException("加密方式 $method 暂不支持下载")
                    }
                }
                line.startsWith("#EXT-X-MAP") -> units += DownloadUnit(
                    url = QUOTED_URI_REGEX.find(line)?.groupValues?.get(1)
                        ?.let { absolutize(baseUrl, it) } ?: return@forEach,
                    keyUri = null,
                    ivHex = null,
                    sequence = 0L,
                )
                line.isNotBlank() && !line.startsWith("#") -> units += DownloadUnit(
                    url = absolutize(baseUrl, line),
                    keyUri = currentKeyUri,
                    ivHex = currentIvHex,
                    sequence = mediaSequence + segmentIndex++,
                )
            }
        }
        return units
    }

    /**
     * 下载单个分片到内存并按需 AES-128 解密（限速节流；失败自动重试 [SEGMENT_RETRY] 次，
     * 间隔重试后仍失败抛出）。并发窗口下先入内存再按序落盘，保证拼接字节序正确。
     */
    private suspend fun fetchSegmentBytes(
        unit: DownloadUnit,
        headers: Map<String, String>,
        limiter: SpeedLimiter,
        keyCache: java.util.concurrent.ConcurrentHashMap<String, ByteArray>,
    ): ByteArray {
        var lastError: Throwable? = null
        repeat(SEGMENT_RETRY + 1) { attempt ->
            try {
                val builder = okhttp3.Request.Builder().url(unit.url)
                if (!isLocalUrl(unit.url)) {
                    headers.forEach { (name, value) -> builder.header(name, value) }
                }
                SpiderHttp.client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("分片下载失败（HTTP ${response.code}）")
                    }
                    val body = response.body ?: throw IllegalStateException("分片下载失败：响应为空")
                    val out = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            limiter.acquire(read)
                        }
                    }
                    val data = out.toByteArray()
                    val keyUri = unit.keyUri ?: return data
                    return HlsAesDecryptor.decrypt(
                        data,
                        fetchKey(keyUri, headers, keyCache),
                        unit.ivHex,
                        unit.sequence,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (attempt < SEGMENT_RETRY) delay(SEGMENT_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("分片下载失败")
    }

    /** 拉取 AES-128 密钥（16 字节；同地址缓存复用，下载失败抛出由分片重试兜底） */
    private suspend fun fetchKey(
        url: String,
        headers: Map<String, String>,
        keyCache: java.util.concurrent.ConcurrentHashMap<String, ByteArray>,
    ): ByteArray {
        keyCache[url]?.let { return it }
        val builder = okhttp3.Request.Builder().url(url)
        if (!isLocalUrl(url)) {
            headers.forEach { (name, value) -> builder.header(name, value) }
        }
        SpiderHttp.client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("密钥下载失败（HTTP ${response.code}）")
            }
            val bytes = response.body?.bytes() ?: throw IllegalStateException("密钥下载失败：响应为空")
            if (bytes.size != 16) {
                throw IllegalStateException("密钥长度异常（${bytes.size} 字节，AES-128 需 16 字节）")
            }
            keyCache[url] = bytes
            return bytes
        }
    }

    /**
     * 获取最终媒体清单：返回 (重定向后最终媒体清单 URL, 正文)。
     * master 跟随首个变体（深度 ≤2）；媒体清单按开关去广告过滤（共享 HlsAdFilter）
     * 并做行内地址绝对化。
     */
    private suspend fun fetchMediaPlaylist(
        target: String,
        headers: Map<String, String>,
        filterEnabled: Boolean,
    ): Pair<String, String> {
        var current = target
        repeat(PLAYLIST_MAX_DEPTH) {
            // 上游可能 302 跳转（gslb 调度类清单）：相对分片/密钥地址必须基于重定向后的最终 URL 解析
            val (finalUrl, body) = fetchText(current, if (isLocalUrl(current)) emptyMap() else headers)
                ?: throw IllegalStateException("播放列表拉取失败")
            if (!body.contains("#EXTM3U")) {
                throw IllegalStateException("响应不是有效的 m3u8 播放列表")
            }
            if (body.contains("#EXT-X-STREAM-INF")) {
                current = body.lineSequence()
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?.let { ref -> if (isLocalUrl(current)) ref else absolutize(finalUrl, ref) }
                    ?: throw IllegalStateException("master 清单中没有变体")
                return@repeat
            }
            val media = if (filterEnabled) HlsAdFilter.filter(body).text else body
            return finalUrl to absolutizeLines(finalUrl, media)
        }
        throw IllegalStateException("播放列表层级过深")
    }

    /** 媒体清单行内地址绝对化：分片相对路径 → 相对当前清单 URL 的绝对地址 */
    private fun absolutizeLines(base: String, body: String): String =
        body.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP") ->
                    QUOTED_URI_REGEX.replace(line) { match -> "URI=\"${absolutize(base, match.groupValues[1])}\"" }
                line.startsWith("#") -> line
                else -> absolutize(base, line)
            }
        }

    // endregion

    // region 产物入库与进度聚合

    /**
     * 下载完成：产物保留在工作目录（纯下载不转码不转封装，localPath 即本地文件路径），
     * App 内直接播放；「转 MP4 置入相册」由用户在下载页按需触发（convertDownloadToMp4）。
     */
    private suspend fun completeDownload(task: DownloadEntity, target: File) {
        dao.updateCompleted(
            task.contentId,
            DownloadEntity.STATUS_COMPLETED,
            target.absolutePath,
            target.length(),
            System.currentTimeMillis(),
        )
    }

    /** 临时文件原子改名（同分区 renameTo，失败兜底复制后删除） */
    private fun moveFile(part: File, target: File) {
        if (part.renameTo(target)) return
        part.copyTo(target, overwrite = true)
        part.delete()
    }

    /**
     * 进度聚合写入器：百分比步进节流落库；字节快照仅用于速度估算
     * （HLS 分片拼接无总字节语义，total 为 0 时 UI 隐藏对应展示）。
     */
    private class ProgressWriter(private val contentId: String) {

        private var percent = 0
        private var bytes = 0L
        private var total = 0L
        private var speed = 0L
        private var lastTickMs = 0L
        private var lastTickBytes = 0L
        private var lastWritePercent = -1

        fun onPercent(value: Int) {
            percent = value
            if (value - lastWritePercent >= PROGRESS_MIN_STEP) {
                lastWritePercent = value
                write()
            }
        }

        fun onBytes(bytesDone: Long, totalBytes: Long) {
            bytes = bytesDone
            total = totalBytes
            tick(bytesDone)
        }

        /** HLS 分片拼接累计字节（无总字节语义，仅速度采样与已下字节展示） */
        fun onProgressBytes(cumulativeBytes: Long) {
            bytes = cumulativeBytes
            tick(cumulativeBytes)
        }

        private fun tick(bytesDone: Long) {
            val now = SystemClock.elapsedRealtime()
            if (lastTickMs > 0L && now - lastTickMs >= SPEED_TICK_MS) {
                speed = (bytesDone - lastTickBytes) * 1000 / (now - lastTickMs)
            }
            lastTickMs = now
            lastTickBytes = bytesDone
        }

        private fun write() {
            ioScope.launch {
                runCatching {
                    dao.updateProgress(
                        contentId,
                        DownloadEntity.STATUS_DOWNLOADING,
                        percent,
                        bytes,
                        total,
                        speed,
                    )
                }
            }
        }
    }

    // endregion

    // region 地址与文件工具

    /** 工作目录内的临时产物路径（重名追加 (n)，含临时文件占用判定） */
    private fun targetPathFor(task: DownloadEntity): String {
        val base = GalleryExporter.sanitizeFileName(task.title.ifBlank { "video" })
        val ext = extensionFor(task)
        var candidate = File(workDir, "$base.$ext")
        var suffix = 1
        while (candidate.exists() || File(candidate.absolutePath + PART_SUFFIX).exists()) {
            candidate = File(workDir, "$base($suffix).$ext")
            suffix++
        }
        return candidate.absolutePath
    }

    /** 产物扩展名：m3u8 → ts；直链按路径后缀（默认 mp4） */
    private fun extensionFor(task: DownloadEntity): String {
        if (isHlsUrl(task.url)) return "ts"
        val path = task.url.substringBefore('?').substringBefore('#').lowercase()
        return listOf("mp4", "flv", "ts", "mkv", "mov", "webm")
            .firstOrNull { path.endsWith(".$it") } ?: "mp4"
    }

    /** HLS 地址判定（# 后参数不参与判定；覆盖 .m3u8 后缀与无后缀 m3u8 端点/代理路由） */
    private fun isHlsUrl(url: String): Boolean = url.substringBefore("#").lowercase().contains("m3u8")

    /** 不支持下载的地址：DASH 清单 / 本地 /hdr 包装流（DASH）；
     *  本地 /m3u8 过滤路由与 /proxy?do=m3u8 类清单代理按 HLS 正常下载（播放器能播即可下） */
    private fun isUnsupportedDownloadUrl(url: String): Boolean {
        if (url.substringBefore('?').substringBefore('#').lowercase().endsWith(".mpd")) return true
        if (!url.startsWith(LOCAL_PREFIX)) return false
        if (url.contains("/m3u8") || url.contains("do=m3u8")) return false
        return url.contains("/proxy") || url.contains("/hdr")
    }

    /** 本地代理地址判定（裸 GET，header 由路由端注入） */
    private fun isLocalUrl(url: String): Boolean = url.startsWith(LOCAL_PREFIX)

    /** GET 全文（失败返回 null）；返回 (跟随重定向后的最终 URL, 正文)，供相对地址解析基准 */
    private suspend fun fetchText(url: String, headers: Map<String, String>): Pair<String, String>? = runCatching {
        val builder = okhttp3.Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        SpiderHttp.client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) null
            else response.request.url.toString() to (response.body?.string() ?: "")
        }
    }.getOrNull()

    /** 相对地址绝对化（URI 解析失败时按目录拼接兜底） */
    private fun absolutize(base: String, ref: String): String {
        if (ref.contains("://")) return ref
        return runCatching { URI(base).resolve(ref).toString() }.getOrElse {
            base.substringBeforeLast('/') + "/" + ref.removePrefix("/")
        }
    }

    /** 请求头 JSON 反序列化（入队时由 JSONObject 序列化） */
    private fun parseHeaders(json: String): Map<String, String> = runCatching {
        if (json.isBlank()) {
            emptyMap()
        } else {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }
    }.getOrDefault(emptyMap())

    /** 当前是否蜂窝移动网络（「仅 WiFi 下载」约束判定） */
    private fun isCellularNetwork(): Boolean = runCatching {
        val cm = ShipingApp.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        val capabilities = cm?.getNetworkCapabilities(cm.activeNetwork)
        capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }.getOrDefault(false)

    // endregion
}
