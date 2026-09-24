package com.shiping.app

import android.content.Context
import com.shiping.app.data.local.AppDatabase
import com.shiping.app.data.local.DataStoreKeyValueStore
import com.shiping.app.data.player.Media3PlayerKernel
import com.shiping.app.data.remote.RetrofitClient
import com.shiping.app.data.repository.AndroidApiSourceRepository
import com.shiping.app.data.repository.AndroidFavoriteRepository
import com.shiping.app.data.repository.AndroidParseSourceRepository
import com.shiping.app.data.repository.AndroidPlayHistoryRepository
import com.shiping.app.data.repository.AndroidTvRepository
import com.shiping.app.data.repository.AndroidVodRepository
import com.shiping.app.data.spider.SpiderHttp
import com.shiping.app.data.spider.SpiderRuntime
import com.shiping.desktop.data.repository.TvBoxConfigImporter
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.graph.ImportResult
import com.shiping.shared.graph.TvBoxImporter
import com.shiping.shared.prefs.KeyValueStore
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.shared.repository.ApiSourceRepository
import com.shiping.shared.repository.FavoriteRepository
import com.shiping.shared.repository.ParseSourceRepository
import com.shiping.shared.repository.PlayHistoryRepository
import com.shiping.shared.repository.TvRepository
import com.shiping.shared.repository.VodRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android 端依赖容器：Room/DataStore 数据栈 + 爬虫运行时 + media3 播放内核。
 * 实现 shared 共享 AppGraph 接口（输出 com.shiping.desktop.data.model 模型），
 * 统一 UI（cmpMain）经 LocalAppGraph 消费，与桌面 AppContainer 同构。
 */
object ShipingGraph : AppGraph {

    /** 应用级协程作用域（Room Flow stateIn / 后台任务） */
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override lateinit var prefs: KeyValueStore
        private set

    /** 数据目录（filesDir，设置页展示用） */
    override lateinit var dataDirPath: String
        private set

    override val apiSourceRepository: ApiSourceRepository by lazy {
        AndroidApiSourceRepository(AppDatabase.getInstance(contextRef!!).apiSourceDao(), prefs, appScope)
    }

    override val parseSourceRepository: ParseSourceRepository by lazy {
        AndroidParseSourceRepository(AppDatabase.getInstance(contextRef!!).parseSourceDao(), prefs, appScope)
    }

    override val tvRepository: TvRepository by lazy {
        AndroidTvRepository(AppDatabase.getInstance(contextRef!!).tvSourceDao(), prefs, appScope)
    }

    override val playHistoryRepository: PlayHistoryRepository by lazy {
        AndroidPlayHistoryRepository(AppDatabase.getInstance(contextRef!!).playHistoryDao(), appScope)
    }

    override val favoriteRepository: FavoriteRepository by lazy {
        AndroidFavoriteRepository(AppDatabase.getInstance(contextRef!!).favoriteDao(), appScope)
    }

    override val vodRepository: VodRepository by lazy {
        AndroidVodRepository(
            RetrofitClient.apiService,
            apiSourceRepository,
            parseSourceRepository,
            prefs,
            SpiderRuntime,
        )
    }

    override val tvBoxImporterService: TvBoxImporter by lazy {
        val importer = TvBoxConfigImporter(apiSourceRepository, parseSourceRepository, tvRepository, SpiderHttp.client)
        object : TvBoxImporter {
            override suspend fun import(url: String, label: String): ImportResult =
                importer.import(url, label).let { ImportResult(it.messageText(), it.warehouses) }
        }
    }

    lateinit var media3Kernel: Media3PlayerKernel
        private set

    /** 启动预热：默认源插入 + 各仓库刷新（AppGraph 契约，启动时后台执行一次） */
    override suspend fun warmUp() {
        apiSourceRepository.initDefaultSource()
        tvRepository.initDefaultSource()
        apiSourceRepository.refresh()
        parseSourceRepository.refresh()
        tvRepository.refresh()
        playHistoryRepository.refresh()
        favoriteRepository.refresh()
    }

    @Volatile
    private var contextRef: Context? = null

    fun init(context: Context) {
        contextRef = context.applicationContext
        prefs = DataStoreKeyValueStore(contextRef!!, appScope)
        dataDirPath = contextRef!!.filesDir.absolutePath
        // m3u8 播放级广告过滤独立开关（KEY_FILTER_M3U8_AD），与列表过滤互不影响
        media3Kernel = Media3PlayerKernel(
            isSliceAdFilterEnabled = { prefs.getBoolean(PrefsKeys.KEY_FILTER_M3U8_AD, true) },
        ).apply { applicationContext = contextRef!! }
    }
}
