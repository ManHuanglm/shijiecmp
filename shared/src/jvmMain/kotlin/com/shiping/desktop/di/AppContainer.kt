package com.shiping.desktop.di

import android.app.Application
import android.content.Context
import com.shiping.desktop.data.db.DesktopDb
import com.shiping.desktop.data.prefs.DesktopPrefs
import com.shiping.desktop.data.remote.CmsClient
import com.shiping.desktop.data.repository.ApiSourceRepository
import com.shiping.desktop.data.repository.FavoriteRepository
import com.shiping.desktop.data.repository.ParseSourceRepository
import com.shiping.desktop.data.repository.PlayHistoryRepository
import com.shiping.desktop.data.repository.TvBoxConfigImporter
import com.shiping.desktop.data.repository.TvRepository
import com.shiping.desktop.data.repository.VodRepository
import com.shiping.desktop.data.spider.DesktopSpiderRuntime
import com.shiping.desktop.data.spider.SpiderHttp
import com.shiping.desktop.util.AppLog
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.graph.ImportResult
import com.shiping.shared.graph.TvBoxImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 桌面版依赖容器：初始化数据目录、stub 上下文、爬虫运行时与全部仓库。
 * 实现 shared AppGraph——统一 UI（cmpMain）经接口消费，不感知桌面存储实现。
 */
class AppContainer private constructor() : AppGraph {

    /** 应用级协程作用域（仓库预热/后台任务） */
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var dataDir: File
        private set

    /** 偏好存储（DesktopPrefs 实现 shared KeyValueStore，协变挂接） */
    override lateinit var prefs: DesktopPrefs
        private set

    override val dataDirPath: String
        get() = dataDir.absolutePath

    lateinit var db: DesktopDb
        private set

    override lateinit var apiSourceRepository: ApiSourceRepository
        private set

    override lateinit var parseSourceRepository: ParseSourceRepository
        private set

    override lateinit var tvRepository: TvRepository
        private set

    override lateinit var playHistoryRepository: PlayHistoryRepository
        private set

    override lateinit var favoriteRepository: FavoriteRepository
        private set

    override lateinit var vodRepository: VodRepository
        private set

    lateinit var tvBoxImporter: TvBoxConfigImporter
        private set

    /** TVBox 导入服务适配：结果 Summary 转跨端 ImportResult（AppGraph 契约） */
    override val tvBoxImporterService: TvBoxImporter = object : TvBoxImporter {
        override suspend fun import(url: String, label: String): ImportResult =
            tvBoxImporter.import(url, label).let { ImportResult(it.messageText(), it.warehouses) }
    }

    /** 启动预热：默认源插入 + 各仓库刷新（AppGraph 契约，启动时后台执行一次） */
    override suspend fun warmUp() {
        apiSourceRepository.initDefaultSource()
        tvRepository.initDefaultSource()
        apiSourceRepository.refresh()
        parseSourceRepository.refresh()
        tvRepository.refresh()
        playHistoryRepository.refresh()
        favoriteRepository.refresh()
        AppLog.i("AppContainer", "数据加载完成：API 源 ${apiSourceRepository.sources.value.size} 个")
    }

    /**
     * 桌面 stub 上下文：数据目录注入给爬虫 jar（Init/Prefers/Path 使用）。
     * 必须是 Application 实例——壳 jar 的 Init.init 内部会 checkcast Application，
     * 传普通 Context 会在引导阶段 ClassCastException，导致 Init.context() 恒为 null。
     */
    private val stubContext: Context = object : Application() {
        override val baseDir: File get() = dataDir
    }

    fun init() {
        // 数据目录：默认 %USERPROFILE%/.shiping-desktop，可被自定义路径覆盖（重启生效）
        val defaultDir = File(System.getProperty("user.home"), ".shiping-desktop")
        val override = readDataDirOverride(defaultDir)
        dataDir = override?.let { File(it).apply { mkdirs() } }?.takeIf { it.isDirectory } ?: defaultDir
        // 文件日志（打包版 exe 无控制台，排障依赖日志文件）
        AppLog.bindFile(File(dataDir, "logs/desktop.log"))
        AppLog.i("AppContainer", "数据目录: ${dataDir.absolutePath}")

        // 爬虫 jar 侧 SharedPreferences 持久化目录
        MemSharedPreferencesHolder.init(dataDir)

        prefs = DesktopPrefs(dataDir)
        db = DesktopDb(File(dataDir, "shiping.db"))

        // 爬虫桥接层钩子：HTTP 代理配置（设置页修改后重启生效）
        com.github.catvod.utils.CatvodHooks.httpProxyResolver = {
            prefs.getString(com.shiping.desktop.data.prefs.DesktopPrefs.KEY_HTTP_PROXY).takeIf { it.isNotBlank() }
        }

        apiSourceRepository = ApiSourceRepository(db, prefs)
        parseSourceRepository = ParseSourceRepository(db, prefs)
        tvRepository = TvRepository(db, prefs)
        playHistoryRepository = PlayHistoryRepository(db)
        favoriteRepository = FavoriteRepository(db)
        vodRepository = VodRepository(CmsClient(), apiSourceRepository, parseSourceRepository, prefs)
        tvBoxImporter = TvBoxConfigImporter(apiSourceRepository, parseSourceRepository, tvRepository, SpiderHttp.client)

        // 爬虫运行时：注入 stub 上下文 + 数据目录，启动本地代理（9978 起）
        DesktopSpiderRuntime.init(stubContext, dataDir)

        // 数据预热：默认源插入 + 各仓库刷新（AppGraph.warmUp）
        appScope.launch {
            runCatching { warmUp() }
                .onFailure { AppLog.e("AppContainer", "数据初始化失败: ${it.message}") }
        }
    }

    companion object {

        /** 单例（应用生命周期一份） */
        @Volatile
        private var instance: AppContainer? = null

        fun get(): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer().also {
                it.init()
                instance = it
            }
        }

        /**
         * 读取自定义数据目录覆盖值。覆盖值固定存于默认目录的 prefs 文件
         * （自定义目录自身尚未初始化时也要能读到），返回 null 表示使用默认目录。
         */
        fun readDataDirOverride(defaultDir: File): String? = runCatching {
            val file = File(defaultDir, "prefs.properties")
            if (!file.exists()) return null
            val props = java.util.Properties()
            java.io.FileInputStream(file).use { props.loadFromXML(it) }
            props.getProperty(com.shiping.desktop.data.prefs.DesktopPrefs.KEY_DATA_DIR)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        /** 保存/清除自定义数据目录覆盖值（空串清除，重启生效） */
        fun writeDataDirOverride(defaultDir: File, path: String) {
            runCatching {
                val file = File(defaultDir, "prefs.properties").apply { parentFile?.mkdirs() }
                val props = java.util.Properties()
                if (file.exists()) java.io.FileInputStream(file).use { props.loadFromXML(it) }
                if (path.isBlank()) {
                    props.remove(com.shiping.desktop.data.prefs.DesktopPrefs.KEY_DATA_DIR)
                } else {
                    props.setProperty(com.shiping.desktop.data.prefs.DesktopPrefs.KEY_DATA_DIR, path)
                }
                java.io.FileOutputStream(file).use { props.storeToXML(it, "shiping-desktop preferences") }
            }
        }
    }
}

/** MemSharedPreferences 目录注入中转（stub 类在 android 包内，避免 UI 层直接依赖） */
object MemSharedPreferencesHolder {
    fun init(dataDir: File) {
        android.content.MemSharedPreferences.storeDir = File(dataDir, "shared_prefs").apply { mkdirs() }
    }
}
