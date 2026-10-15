package com.shiping.desktop.data.download

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shiping.desktop.data.db.DesktopDb
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.spider.DesktopProxyRoutes
import com.shiping.desktop.util.AppLog
import com.shiping.shared.download.DownloadStatus
import com.shiping.shared.download.SpeedLimiter
import com.shiping.shared.player.HlsAdFilter
import com.shiping.shared.player.HlsAesDecryptor
import com.shiping.shared.prefs.KeyValueStore
import com.shiping.shared.prefs.PrefsKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/**
 * 桌面下载引擎：SQLite downloads 表驱动的并发队列。
 * - 并发任务数可配置（KEY_DOWNLOAD_MAX_PARALLEL 1-3）；HLS 分片并发下载可配置（1-6）
 * - mp4 等直链经 SpiderHttp 下载（Range 断点续传 + 首块嗅探）；m3u8 经本地 /m3u8 过滤路由
 *   取清单后分片并发下载拼接为 .ts（分片失败重试 + 分片级断点续传，.part 保留至完成/重启）
 * - 进度按百分比步进 ≥1 节流落库并刷新 StateFlow；速度 800ms 采样
 * - 暂停/删除取消进行中任务；启动时残留「下载中」置失败并清理 .part
 * - 限速/并发等档位每次任务现读偏好；入队检查磁盘最低预留 1GB
 */
class DesktopDownloadEngine(
    private val db: DesktopDb,
    private val prefs: KeyValueStore,
    private val dataDir: File,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    companion object {
        private const val TAG = "DesktopDownloadEngine"

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
    }

    private val dao = DesktopDownloadDao(db)

    private val _tasks = MutableStateFlow<List<DesktopDownloadRecord>>(emptyList())

    /** 全部下载任务（创建时间倒序），下载管理页数据源 */
    val tasks: StateFlow<List<DesktopDownloadRecord>> = _tasks.asStateFlow()

    /** 下载产物目录：偏好自定义目录（KEY_DOWNLOAD_DIR），未配置则 <dataDir>/downloads（每次现读，改目录即时生效） */
    private fun outputDir(): File = prefs.getString(DesktopPrefs.KEY_DOWNLOAD_DIR, "")
        .takeIf { it.isNotBlank() }
        ?.let { File(it).apply { mkdirs() } }
        ?: File(dataDir, "downloads").apply { mkdirs() }

    /** 队列 worker 集合（数量补足至并发任务数档位） */
    private val workers = mutableListOf<Job>()

    /** 进行中任务 → 所属 worker（暂停/删除取消用） */
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** 启动初始化：残留「下载中」置失败（.part 保留，重试即断点续传）+ 刷新列表 + 自动恢复队列 */
    fun init() {
        scope.launch {
            runCatching {
                dao.resetStatuses(
                    listOf(DownloadStatus.DOWNLOADING),
                    DownloadStatus.FAILED,
                    "下载被中断（应用退出），点重试从断点续传",
                )
            }.onFailure { AppLog.w(TAG, "中断任务清理失败: ${it.message}") }
            reload()
            kick()
        }
    }

    /**
     * 入队下载任务（同任务已完成/排队中则拒绝，暂停/失败则重置重下）。
     * @return 用户提示文案
     */
    suspend fun enqueue(record: DesktopDownloadRecord): String = withContext(Dispatchers.IO) {
        when {
            outputDir().usableSpace < MIN_FREE_DISK_BYTES -> "磁盘剩余空间不足 1GB，无法下载"
            else -> {
                val existing = dao.getById(record.contentId)
                when {
                    existing != null && existing.status == DownloadStatus.COMPLETED -> "该集已在下载列表中"
                    existing != null && (existing.status == DownloadStatus.DOWNLOADING ||
                        existing.status == DownloadStatus.QUEUED) -> "该集正在下载中"
                    else -> {
                        dao.insert(record)
                        reload()
                        kick()
                        "已加入下载队列"
                    }
                }
            }
        }
    }

    /** 暂停任务：取消所属 worker 当前下载（HLS 保留 .part 与分片计数，恢复续传），排队中仅改状态 */
    fun pause(contentId: String) {
        scope.launch {
            activeJobs[contentId]?.cancel()
            runCatching { dao.updateStatus(contentId, DownloadStatus.PAUSED) }
            reload()
            kick()
        }
    }

    /** 恢复/重试任务：重新排队（HLS 从已完成分片续传）并唤醒队列 */
    fun resume(contentId: String) {
        scope.launch {
            runCatching { dao.updateStatus(contentId, DownloadStatus.QUEUED) }
            reload()
            kick()
        }
    }

    /** 删除任务：取消进行中下载 + 删除本地产物（含临时文件） + 删库记录 */
    fun delete(contentId: String) {
        scope.launch {
            activeJobs[contentId]?.cancel()
            runCatching {
                dao.getById(contentId)?.let { record ->
                    record.localPath.takeIf { it.isNotBlank() }?.let { path -> File(path).delete() }
                    baseFileFor(record).let { file -> File(file.absolutePath + PART_SUFFIX).delete() }
                }
            }.onFailure { AppLog.w(TAG, "下载文件清理失败: ${it.message}") }
            runCatching { dao.delete(contentId) }
            reload()
            kick()
        }
    }

    /** 按 ID 查任务（本地产物打开等场景） */
    fun getById(contentId: String): DesktopDownloadRecord? = dao.getById(contentId)

    // region 队列执行

    /** 唤醒队列（幂等：worker 数不足并发任务档位时补足；设置变化后同样经此生效） */
    fun kick() {
        synchronized(this) {
            workers.removeAll { !it.isActive }
            while (workers.size < maxParallel()) {
                workers += scope.launch { runQueue() }
            }
        }
    }

    /** 并发任务数档位（现读偏好，1-3） */
    private fun maxParallel(): Int = prefs.getInt(PrefsKeys.KEY_DOWNLOAD_MAX_PARALLEL, 1).coerceIn(1, 3)

    /** 同步取下一个排队任务并置下载中（防多 worker 重复领取） */
    private fun pickNextQueued(): DesktopDownloadRecord? = synchronized(this) {
        val task = dao.getAll().firstOrNull { it.status == DownloadStatus.QUEUED } ?: return null
        dao.updateStatus(task.contentId, DownloadStatus.DOWNLOADING)
        reload()
        task
    }

    private suspend fun runQueue() {
        while (true) {
            val task = pickNextQueued() ?: break
            activeJobs[task.contentId] = kotlin.coroutines.coroutineContext[Job]!!
            try {
                performDownload(task)
            } catch (e: CancellationException) {
                // 暂停/删除取消：置为暂停态（NonCancellable 保证取消上下文中落库成功）
                withContext(NonCancellable) {
                    runCatching { dao.updateStatus(task.contentId, DownloadStatus.PAUSED) }
                    reload()
                }
                throw e
            } catch (t: Throwable) {
                AppLog.e(TAG, "下载失败 ${task.title}: ${t.message}\n${t.stackTraceToString()}")
                runCatching {
                    dao.updateFailed(task.contentId, DownloadStatus.FAILED, t.message ?: "未知错误")
                    reload()
                }
            } finally {
                activeJobs.remove(task.contentId)
            }
        }
    }

    /** 下载单任务：按地址类型分流（每次现读限速档位；目标路径首次分配后落库，续传稳定复用防重名漂移） */
    private suspend fun performDownload(task: DesktopDownloadRecord) {
        if (isUnsupportedDownloadUrl(task.url)) {
            throw IllegalStateException("该播放地址（DASH/代理流）暂不支持下载")
        }
        val headers = parseHeaders(task.headersJson)
        val limiter = SpeedLimiter(prefs.getInt(PrefsKeys.KEY_DOWNLOAD_SPEED_LIMIT_KB, 0).toLong() * 1024L)
        val targetPath = task.localPath.ifBlank { targetFileFor(task).absolutePath }
        if (task.localPath.isBlank()) {
            runCatching { dao.updateLocalPath(task.contentId, targetPath) }
        }
        if (isHlsUrl(task.url)) {
            performHls(task, targetPath, headers, limiter)
        } else {
            performDirect(task, targetPath, headers, limiter)
        }
    }

    /**
     * 直链下载：临时 .part 节流写入 → 原子改名落地。
     * 首块内容嗅探：响应体实为 m3u8 文本（无后缀 HLS 端点/伪装直链）时转 HLS 分片下载，
     * 网页错误页直接判失败——避免把 2KB 文本当视频落盘成假「已下载」。
     * 断点续传：.part 存在时带 Range 头续传（服务器 206 才续，200 截断重来）。
     */
    private suspend fun performDirect(
        task: DesktopDownloadRecord,
        targetPath: String,
        headers: Map<String, String>,
        limiter: SpeedLimiter,
    ) {
        val target = File(targetPath)
        val part = File(target.absolutePath + PART_SUFFIX)
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
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (hlsDetected) {
                performHls(task, targetPath, headers, limiter)
                return
            }
            writer.onPercent(99)
            dao.updateCompleted(
                task.contentId,
                DownloadStatus.COMPLETED,
                target.absolutePath,
                target.length(),
                System.currentTimeMillis(),
            )
            reload()
        } finally {
            // 失败/暂停保留 .part 供断点续传；成功 move 后 part 自然不存在
            if (hlsDetected && part.exists()) runCatching { part.delete() }
        }
    }

    /**
     * m3u8 下载：清单经本地 /m3u8 过滤路由（自动去广告，路由重写分片绝对化/包装）
     * → 分片并发下载（滑动窗口按序写入）拼接为单文件 .ts；
     * 分片失败自动重试；AES-128 加密流自动拉取密钥并解密（SAMPLE-AES 等暂不支持）；
     * 失败/暂停保留 .part 与已完成分片计数，恢复时从断点分片继续追加（分片级续传）。
     */
    private suspend fun performHls(
        task: DesktopDownloadRecord,
        targetPath: String,
        headers: Map<String, String>,
        limiter: SpeedLimiter,
    ) {
        val filterEnabled = prefs.getBoolean(PrefsKeys.KEY_FILTER_M3U8_AD, true)
        val decorated = DesktopProxyRoutes.decoratePlayUrl(task.url, headers, filterEnabled)
        val (playlistUrl, playlist) = fetchMediaPlaylist(decorated, headers)
        val units = parseDownloadUnits(playlist, playlistUrl)
        if (units.isEmpty()) {
            throw IllegalStateException("播放列表中没有可下载的分片")
        }
        val target = File(targetPath)
        val part = File(target.absolutePath + PART_SUFFIX)
        // 分片级断点续传：.part 存在时从已完成分片继续追加；.part 丢失（重启清理）则从头重来
        var startFrom = task.completedSegments.coerceIn(0, units.size)
        if (startFrom > 0 && !part.exists()) startFrom = 0
        val appending = startFrom > 0
        val writer = ProgressWriter(task.contentId)
        val concurrency = prefs.getInt(PrefsKeys.KEY_DOWNLOAD_SEGMENT_CONCURRENCY, 3).coerceIn(1, 6)
        var doneUnits = startFrom
        var bytesDone = if (appending) part.length() else 0L
        try {
            withContext(Dispatchers.IO) {
                val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
                java.io.FileOutputStream(part, appending).use { output ->
                    // 滑动窗口：并发预取未来分片，按序 await 写入（保证字节顺序正确）
                    val pending = java.util.ArrayDeque<kotlinx.coroutines.Deferred<ByteArray>>()
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
                        dao.updateSegmentProgress(task.contentId, doneUnits, bytesDone)
                        writer.onProgressBytes(bytesDone)
                        writer.onPercent(doneUnits * 98 / units.size)
                        fillWindow()
                    }
                    output.flush()
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            writer.onPercent(99)
            dao.updateCompleted(
                task.contentId,
                DownloadStatus.COMPLETED,
                target.absolutePath,
                target.length(),
                System.currentTimeMillis(),
            )
            reload()
        } finally {
            // 失败/暂停保留 .part 与分片计数（resume 断点续传）；重启时由 init 统一清理
        }
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
                    return HlsAesDecryptor.decrypt(data, fetchKey(keyUri, headers, keyCache), unit.ivHex, unit.sequence)
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

    /** 获取最终媒体清单：返回 (重定向后最终媒体清单 URL, 正文)；master 跟随首个变体（深度 ≤2） */
    private fun fetchMediaPlaylist(target: String, headers: Map<String, String>): Pair<String, String> {
        var current = target
        repeat(PLAYLIST_MAX_DEPTH) {
            // 上游可能 302 跳转（gslb 调度类清单）：相对分片/密钥地址必须基于重定向后的最终 URL 解析
            val (finalUrl, body) = fetchText(current, if (isLocalUrl(current)) emptyMap() else headers)
                ?: throw IllegalStateException("播放列表拉取失败")
            if (!body.contains("#EXTM3U")) {
                throw IllegalStateException("响应不是有效的 m3u8 播放列表")
            }
            if (!body.contains("#EXT-X-STREAM-INF")) {
                return finalUrl to if (isLocalUrl(current)) body else postProcessRawPlaylist(finalUrl, body)
            }
            val variant = body.lineSequence()
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw IllegalStateException("master 清单中没有变体")
            current = if (isLocalUrl(current)) variant else absolutize(finalUrl, variant)
        }
        throw IllegalStateException("播放列表层级过深")
    }

    /** 原始清单兜底处理：共享 HlsAdFilter 过滤 + 行内地址绝对化（对齐本地 /m3u8 路由行为） */
    private fun postProcessRawPlaylist(base: String, body: String): String {
        val filtered = HlsAdFilter.filter(body).text
        return filtered.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP") ->
                    QUOTED_URI_REGEX.replace(line) { match -> "URI=\"${absolutize(base, match.groupValues[1])}\"" }
                line.startsWith("#") -> line
                else -> absolutize(base, line)
            }
        }
    }

    // endregion

    // region 进度聚合

    /**
     * 进度聚合写入器：百分比步进节流落库；字节快照仅用于速度估算
     * （HLS 分片拼接无总字节语义，total 为 0 时 UI 隐藏对应展示）。
     */
    private inner class ProgressWriter(private val contentId: String) {

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
            val now = System.currentTimeMillis()
            if (lastTickMs > 0L && now - lastTickMs >= SPEED_TICK_MS) {
                speed = (bytesDone - lastTickBytes) * 1000 / (now - lastTickMs)
            }
            lastTickMs = now
            lastTickBytes = bytesDone
        }

        private fun write() {
            runCatching {
                dao.updateProgress(contentId, DownloadStatus.DOWNLOADING, percent, bytes, total, speed)
                reload()
            }
        }
    }

    // endregion

    // region 地址与文件工具

    /** 刷新任务列表 StateFlow */
    private fun reload() {
        _tasks.value = dao.getAll()
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
    private fun fetchText(url: String, headers: Map<String, String>): Pair<String, String>? = runCatching {
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

    /** 无重名处理的产物文件（清理临时文件用） */
    private fun baseFileFor(record: DesktopDownloadRecord): File =
        File(outputDir(), "${sanitizeFileName(record.title)}.${extensionFor(record)}")

    /** 产物目标文件（重名追加 (n)，含临时文件占用判定） */
    private fun targetFileFor(record: DesktopDownloadRecord): File {
        val base = sanitizeFileName(record.title)
        val ext = extensionFor(record)
        var candidate = File(outputDir(), "$base.$ext")
        var suffix = 1
        while (candidate.exists() || File(candidate.absolutePath + PART_SUFFIX).exists()) {
            candidate = File(outputDir(), "$base($suffix).$ext")
            suffix++
        }
        return candidate
    }

    /** 产物扩展名：m3u8 → ts；直链按路径后缀（默认 mp4） */
    private fun extensionFor(record: DesktopDownloadRecord): String {
        if (isHlsUrl(record.url)) return "ts"
        val path = record.url.substringBefore('?').substringBefore('#').lowercase()
        return listOf("mp4", "flv", "ts", "mkv", "mov", "webm")
            .firstOrNull { path.endsWith(".$it") } ?: "mp4"
    }

    /** 文件名清洗（非法字符替换 + 长度截断，对齐 Android GalleryExporter） */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "video" }

    /** 请求头 JSON 反序列化（入队时由 Gson 序列化） */
    private fun parseHeaders(json: String): Map<String, String> = runCatching {
        if (json.isBlank()) {
            emptyMap()
        } else {
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<HashMap<String, String>>(json, type).orEmpty()
        }
    }.getOrDefault(emptyMap())

    // endregion
}
