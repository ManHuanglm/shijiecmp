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
import com.shiping.app.data.spider.SpiderRuntime
import com.shiping.shared.prefs.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android 端依赖容器：Room/DataStore 数据栈 + 爬虫运行时 + media3 播放内核。
 * 全部仓库实现 shared 共享接口（输出 com.shiping.desktop.data.model 模型），
 * M3b UI 统一后经此容器（或其 AppGraph 抽象）消费。
 */
object ShipingGraph {

    /** 应用级协程作用域（Room Flow stateIn / 后台任务） */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var prefs: KeyValueStore
        private set

    lateinit var media3Kernel: Media3PlayerKernel
        private set

    val apiSourceRepository by lazy {
        AndroidApiSourceRepository(AppDatabase.getInstance(contextRef!!).apiSourceDao(), prefs, appScope)
    }

    val parseSourceRepository by lazy {
        AndroidParseSourceRepository(AppDatabase.getInstance(contextRef!!).parseSourceDao(), prefs, appScope)
    }

    val tvRepository by lazy {
        AndroidTvRepository(AppDatabase.getInstance(contextRef!!).tvSourceDao(), prefs, appScope)
    }

    val playHistoryRepository by lazy {
        AndroidPlayHistoryRepository(AppDatabase.getInstance(contextRef!!).playHistoryDao(), appScope)
    }

    val favoriteRepository by lazy {
        AndroidFavoriteRepository(AppDatabase.getInstance(contextRef!!).favoriteDao(), appScope)
    }

    val vodRepository by lazy {
        AndroidVodRepository(
            RetrofitClient.apiService,
            apiSourceRepository,
            parseSourceRepository,
            prefs,
            SpiderRuntime,
        )
    }

    @Volatile
    private var contextRef: Context? = null

    fun init(context: Context) {
        contextRef = context.applicationContext
        prefs = DataStoreKeyValueStore(contextRef!!, appScope)
        media3Kernel = Media3PlayerKernel().apply { applicationContext = contextRef!! }
    }
}
