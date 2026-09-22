package com.shiping.shared.graph

import com.shiping.shared.prefs.KeyValueStore
import com.shiping.shared.repository.ApiSourceRepository
import com.shiping.shared.repository.FavoriteRepository
import com.shiping.shared.repository.ParseSourceRepository
import com.shiping.shared.repository.PlayHistoryRepository
import com.shiping.shared.repository.TvRepository
import com.shiping.shared.repository.VodRepository
import kotlinx.coroutines.CoroutineScope

/**
 * TVBox 订阅导入统一入口（桌面/Android 各自实现）。
 * 返回人类可读的结果消息（成功条数/跳过原因），供设置页直接展示。
 */
interface TvBoxImporter {

    /** 拉取并导入订阅配置（label 为展示用标签，如「饭太硬」），返回结果消息 */
    suspend fun import(url: String, label: String = ""): String
}

/**
 * 应用依赖图统一接口（jvmCommon）：统一 UI（cmpMain）经此消费数据层，
 * 桌面端由 AppContainer 实现（SQLite/Properties 栈），Android 端由
 * ShipingGraph 实现（Room/DataStore 栈，M4 已就绪）。
 * 平台能力（窗口/文件选择/粘贴板）不在本接口，由 expect/actual 平台钩子另行提供。
 */
interface AppGraph {

    /** 应用级协程作用域（仓库预热/后台任务） */
    val appScope: CoroutineScope

    /** 偏好存储（键名经 PrefsKeys 对齐） */
    val prefs: KeyValueStore

    /** 数据目录路径（设置页展示与日志定位用） */
    val dataDirPath: String

    val apiSourceRepository: ApiSourceRepository
    val parseSourceRepository: ParseSourceRepository
    val tvRepository: TvRepository
    val playHistoryRepository: PlayHistoryRepository
    val favoriteRepository: FavoriteRepository
    val vodRepository: VodRepository

    /** TVBox 订阅导入服务（命名避开各端既有 tvBoxImporter 属性，经适配挂接） */
    val tvBoxImporterService: TvBoxImporter

    /** 启动预热：默认源插入 + 各仓库刷新（两端启动时调用一次） */
    suspend fun warmUp()
}
