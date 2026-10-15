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
 * 订阅导入结果（jvmCommon 跨端）：人类可读消息 + 多仓子配置。
 * warehouses 非空表示多仓配置，设置页展开子配置列表供用户选择。
 */
data class ImportResult(
    val message: String,
    val warehouses: List<Pair<String, String>> = emptyList(),
)

/**
 * TVBox 订阅导入统一入口（桌面/Android 各自实现）。
 */
interface TvBoxImporter {

    /** 拉取并导入订阅配置（label 为展示用标签，如「饭太硬」），返回结果 */
    suspend fun import(url: String, label: String = ""): ImportResult

    /** 剪贴板批量导入：直接解析配置文本（多仓 JSON 内容/合成多仓串），多仓返回仓库列表 */
    suspend fun importRaw(content: String, label: String = ""): ImportResult
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
