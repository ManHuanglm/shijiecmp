package com.shiping.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.Category
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.ui.common.EmptyView
import com.shiping.desktop.ui.common.ErrorView
import com.shiping.desktop.ui.common.LoadingView
import com.shiping.desktop.ui.common.PullRefreshContainer
import com.shiping.desktop.ui.common.TextWidthTabRow
import com.shiping.desktop.ui.common.VideoCard
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.prefs.PrefsKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 首页 UI 状态 */
data class HomeUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: Int = 0,
    val items: List<Vod> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val currentSourceUrl: String = "",
)

/**
 * 首页海报布局档位（源列表弹层循环切换并持久化）：最小/小/中/大四档封面尺寸。
 * [aspectRatio] 封面宽高比（3:4=0.75、4:3≈1.33）；
 * [minCellWidth] 单卡最小宽度，列数由容器宽自动换算（GridCells.Adaptive），不限制列。
 */
enum class PosterLayout(
    val aspectRatio: Float,
    val minCellWidth: Dp,
    val icon: ImageVector,
    val label: String,
) {
    MIN(3f / 4f, 96.dp, Icons.Outlined.GridView, "最小"),
    SMALL(3f / 4f, 140.dp, Icons.Outlined.CalendarViewWeek, "小"),
    MEDIUM(4f / 3f, 180.dp, Icons.Outlined.ViewColumn, "中"),
    LARGE(4f / 3f, 240.dp, Icons.Outlined.CropPortrait, "大"),
}

/** 源使用历史保留条数 */
private const val RECENT_SOURCE_LIMIT = 20

/** action 协议登入类标记（网盘源「登入自己网盘」等），点击跳网盘配置页 */
private const val ACTION_LOGIN = "login"

/** action 返回提示最大展示长度 */
private const val ACTION_MESSAGE_MAX_LEN = 80

/** SHA-256 摘要（小写十六进制），用于加锁源查看密码的哈希存储 */
private fun sha256Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** 首页顶栏可弹出的底部面板 */
private enum class HomeSheet { SOURCE_LIST, HISTORY, CATEGORY_MANAGER }

/** 屏蔽分类密码流程：验证进入 / 设置新密码 / 更新前验证旧密码 */
private enum class BlockedPwdFlow { VERIFY_UNLOCK, SET_NEW, VERIFY_OLD }

/** 首页状态管理：源切换、分类筛选、分页加载 */
class HomeViewModel(
    private val scope: CoroutineScope,
    private val graph: AppGraph,
) {
    private val vodRepository = graph.vodRepository
    private val apiRepository = graph.apiSourceRepository
    private val prefs = graph.prefs

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val sources: StateFlow<List<ApiSourceEntity>> = apiRepository.sources

    /** 源使用历史（最近使用在前，持久化，顶栏图标弹层快速切换） */
    private val _recentSourceIds = MutableStateFlow(loadRecentSourceIds())
    val recentSourceIds: StateFlow<List<Long>> = _recentSourceIds.asStateFlow()

    /** 首页海报布局档位 */
    private val _posterLayout = MutableStateFlow(
        PosterLayout.entries.getOrElse(prefs.getInt(PrefsKeys.KEY_HOME_POSTER_LAYOUT, 0)) { PosterLayout.MEDIUM },
    )
    val posterLayout: StateFlow<PosterLayout> = _posterLayout.asStateFlow()

    /** 源列表弹层加锁源是否已解锁显示（会话级，需密码验证，点锁图标可重新隐藏） */
    private val _lockedVisible = MutableStateFlow(false)
    val lockedVisible: StateFlow<Boolean> = _lockedVisible.asStateFlow()

    /** 当前源 ID（持久化值） */
    val currentSourceId: Long? get() = apiRepository.currentApiId

    init {
        selectSource(apiRepository.getCurrentApiSource(), silent = true)
    }

    /** 切换源（并持久化 + 记入使用历史）；silent=true 用于初始化首拉不提示 */
    fun selectSource(source: ApiSourceEntity?, silent: Boolean = false) {
        if (source != null) {
            if (!silent) apiRepository.setCurrentApiId(source.id)
            recordSourceHistory(source.id)
        }
        _uiState.update { it.copy(categories = emptyList(), selectedCategoryId = 0, items = emptyList(), page = 1, pageCount = 1) }
        load(page = 1, append = false)
    }

    /** 循环切换首页海报布局（四档：最小→小→中→大）并持久化 */
    fun cyclePosterLayout() {
        val next = PosterLayout.entries[(_posterLayout.value.ordinal + 1) % PosterLayout.entries.size]
        _posterLayout.value = next
        prefs.put(PrefsKeys.KEY_HOME_POSTER_LAYOUT, next.ordinal)
    }

    /** 是否已设置加锁源查看密码（决定锁图标弹层是首次设置还是验证模式） */
    fun hasLockPassword(): Boolean = prefs.getString(PrefsKeys.KEY_SOURCE_LOCK_PASSWORD, "").isNotBlank()

    /**
     * 解锁显示加锁源：未设密码时首次输入即为设置密码，之后需验证通过才返回 true。
     * 密码仅存 SHA-256 哈希，验证通过后本次会话内可见，直至点锁图标重新隐藏。
     */
    fun unlockLockedSources(password: String): Boolean {
        val stored = prefs.getString(PrefsKeys.KEY_SOURCE_LOCK_PASSWORD, "")
        if (stored.isBlank()) {
            prefs.put(PrefsKeys.KEY_SOURCE_LOCK_PASSWORD, sha256Hex(password))
            _lockedVisible.value = true
            return true
        }
        val matched = stored == sha256Hex(password)
        if (matched) _lockedVisible.value = true
        return matched
    }

    /** 重新隐藏加锁源（无需密码） */
    fun hideLockedSources() {
        _lockedVisible.value = false
    }

    /** 分类拖动排序：更新当前分类顺序并按源持久化 */
    fun moveCategory(fromIndex: Int, toIndex: Int) {
        val list = _uiState.value.categories.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        list.add(toIndex, list.removeAt(fromIndex))
        _uiState.update { it.copy(categories = list) }
        val sourceUrl = _uiState.value.currentSourceUrl
        if (sourceUrl.isNotBlank()) {
            prefs.put(categoryOrderKey(sourceUrl), list.joinToString(",") { it.typeId.toString() })
        }
    }

    /** 记录源使用历史：去重置顶，超长截断并持久化 */
    private fun recordSourceHistory(id: Long) {
        val updated = (listOf(id) + _recentSourceIds.value.filter { it != id }).take(RECENT_SOURCE_LIMIT)
        _recentSourceIds.value = updated
        prefs.put(PrefsKeys.KEY_API_SOURCE_HISTORY, updated.joinToString(","))
    }

    private fun loadRecentSourceIds(): List<Long> =
        prefs.getString(PrefsKeys.KEY_API_SOURCE_HISTORY, "")
            .split(',')
            .mapNotNull { it.toLongOrNull() }

    /** 按持久化顺序重排分类（未记录的保持原相对顺序排在其后） */
    private fun applyCategoryOrder(categories: List<Category>, sourceUrl: String): List<Category> {
        if (categories.isEmpty() || sourceUrl.isBlank()) return categories
        val savedIds = prefs.getString(categoryOrderKey(sourceUrl), "")
            .split(',')
            .mapNotNull { it.toIntOrNull() }
        if (savedIds.isEmpty()) return categories
        val byId = categories.associateBy { it.typeId }
        return savedIds.mapNotNull { byId[it] } + categories.filter { it.typeId !in savedIds }
    }

    private fun categoryOrderKey(sourceUrl: String) = PrefsKeys.KEY_HOME_CATEGORY_ORDER + sourceUrl

    /** 切换分类 */
    fun selectCategory(category: Category) {
        _uiState.update { it.copy(selectedCategoryId = category.typeId, items = emptyList(), page = 1, pageCount = 1) }
        load(page = 1, append = false)
    }

    /** 触底加载下一页 */
    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || state.page >= state.pageCount) return
        load(page = state.page + 1, append = true)
    }

    /** 下拉刷新：重新拉取当前分类第一页（保留旧列表展示，仅转指示器） */
    fun refresh() {
        val state = _uiState.value
        if (state.loading || state.refreshing) return
        load(page = 1, append = false, refreshing = true)
    }

    private fun load(page: Int, append: Boolean, refreshing: Boolean = false) {
        scope.launch {
            _uiState.update {
                it.copy(
                    loading = !append && !refreshing,
                    loadingMore = append,
                    refreshing = refreshing,
                    error = null,
                )
            }
            val source = apiRepository.getCurrentApiSource()
            runCatching {
                vodRepository.getHome(page = page, typeId = _uiState.value.selectedCategoryId)
            }.onSuccess { response ->
                _uiState.update { state ->
                    val existing = if (append) state.items.map { it.vodId }.toSet() else emptySet()
                    val fresh = response.list.filter { it.vodId !in existing }
                    // 分页信息缺失的源（部分 payload 的 categoryContent 不带 pagecount）：
                    // 有新内容则乐观放行下一页；空页或整页重复则封顶终止，防止无限触发
                    val stop = append && (response.list.isEmpty() || fresh.isEmpty())
                    val pageCount = when {
                        response.pageCount > 1 -> response.pageCount
                        stop -> page
                        else -> page + 1
                    }
                    state.copy(
                        loading = false,
                        loadingMore = false,
                        refreshing = false,
                        items = if (append) state.items + fresh else response.list,
                        page = page,
                        pageCount = pageCount.coerceAtLeast(1),
                        categories = applyCategoryOrder(response.classList.ifEmpty { state.categories }, source?.url.orEmpty()),
                        currentSourceUrl = source?.url.orEmpty(),
                        error = if (!append && response.list.isEmpty()) "暂无内容" else null,
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(loading = false, loadingMore = false, refreshing = false, error = e.message ?: "加载失败") }
            }
        }
    }
}

/** 首页：顶栏（历史源/源切换弹层入口 + 快捷导航）+ 分类标签 + 视频网格（触底自动翻页）；
 *  数据经 AppViewModel 单例跨导航缓存，下拉刷新重新拉取当前分类第一页 */
@Composable
fun HomeScreen(app: AppViewModel) {
    val graph = app.graph
    val scope = rememberCoroutineScope()
    val viewModel = app.homeViewModel

    val uiState by viewModel.uiState.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val recentSourceIds by viewModel.recentSourceIds.collectAsState()
    val posterLayout by viewModel.posterLayout.collectAsState()
    val lockedVisible by viewModel.lockedVisible.collectAsState()
    val gridState = rememberLazyGridState()
    var activeSheet by remember { mutableStateOf<HomeSheet?>(null) }
    // 锁图标密码框：null=不显示，true=首次设置密码，false=验证密码
    var lockSetMode by remember { mutableStateOf<Boolean?>(null) }

    // 分类管理（图1）：隐藏分类（会话级）+ 分类管理弹层开关
    val prefs = app.graph.prefs
    var hiddenCategoryIds by remember { mutableStateOf(setOf<Int>()) }
    // 分类管理（图2）：屏蔽分类（密码保护，按分类名持久化、跨源生效）+ 屏蔽页开关与密码流程
    var blockedCategoryNames by remember {
        mutableStateOf(
            prefs.getString(PrefsKeys.KEY_BLOCKED_CATEGORIES)
                .split(',')
                .filter(String::isNotBlank)
                .toSet(),
        )
    }
    val toggleBlockCategory: (String) -> Unit = { name ->
        val next = if (name in blockedCategoryNames) blockedCategoryNames - name else blockedCategoryNames + name
        blockedCategoryNames = next
        prefs.put(PrefsKeys.KEY_BLOCKED_CATEGORIES, next.joinToString(","))
    }
    var showBlockedManager by remember { mutableStateOf(false) }
    var blockedPwdFlow by remember { mutableStateOf<BlockedPwdFlow?>(null) }
    var blockedPwdError by remember { mutableStateOf<String?>(null) }
    // 分类行展示位：排除屏蔽（密码保护）与会话级隐藏分类
    val visibleCategories = uiState.categories.filter {
        it.typeName !in blockedCategoryNames && it.typeId !in hiddenCategoryIds
    }
    // 当前选中分类不可见时自动回退第一个可见分类
    LaunchedEffect(uiState.categories, blockedCategoryNames, hiddenCategoryIds) {
        if (visibleCategories.none { it.typeId == uiState.selectedCategoryId }) {
            visibleCategories.firstOrNull()?.let(viewModel::selectCategory)
        }
    }

    // 触底检测：倒数第 8 个可见时加载下一页；items 变化后仍处于底部时继续触发
    // （derivedStateOf 的布尔值不变则 LaunchedEffect 不会重跑，须把列表尺寸加入 key）
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(shouldLoadMore, uiState.items.size) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HomeTopBar(app = app, viewModel = viewModel, sources = sources, onShowSheet = { activeSheet = it })
        // 分类行：入口只保留源列表弹层的「分类管理」（含 展示/隐藏/拖动排序/屏蔽分类）
        if (visibleCategories.isNotEmpty()) {
            CategoryTabs(
                categories = visibleCategories,
                selectedId = uiState.selectedCategoryId,
                onSelect = viewModel::selectCategory,
            )
        }
        // 下拉刷新：缓存数据直接展示，重新拉取当前分类第一页
        PullRefreshContainer(
            refreshing = uiState.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            when {
                uiState.loading -> LoadingView()
                uiState.error != null && uiState.items.isEmpty() ->
                    ErrorView(message = uiState.error!!, onRetry = { viewModel.selectSource(null, silent = true) })
                uiState.items.isEmpty() -> EmptyView("该源暂无数据")
                else -> LazyVerticalGrid(
                    // 卡片最小宽自动换列：不限制列，剩余空间均分到各列（满宽无黑边）
                    columns = GridCells.Adaptive(posterLayout.minCellWidth),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                        items(count = uiState.items.size, key = { index -> "${uiState.items[index].vodId}_$index" }) { index ->
                            val vod = uiState.items[index]
                            VideoCard(
                                vod = vod,
                                portraitRatio = posterLayout.aspectRatio,
                                onClick = {
                                val action = vod.action
                                when {
                                    // action 协议项（网盘源登入/清除 Cookie）：登入跳配置页，其余交爬虫处理
                                    action.contains(ACTION_LOGIN, ignoreCase = true) ->
                                        app.openSettingsPage(SettingsPage.PAN_CONFIG)
                                    action.isNotBlank() -> scope.launch {
                                        val result = runCatching {
                                            graph.vodRepository.spiderAction(uiState.currentSourceUrl, vod.vodId, action)
                                        }
                                        result.onFailure { app.showMessage("操作失败：${it.message ?: "未知错误"}") }
                                        app.showMessage(
                                            result.getOrNull()?.takeIf { it.isNotBlank() }?.take(ACTION_MESSAGE_MAX_LEN)
                                                ?: "已执行「${vod.vodName}」",
                                        )
                                    }
                                    else -> app.push(Screen.Detail(vod.vodId, uiState.currentSourceUrl, vod.vodName, vod.safePic))
                                }
                            },
                        )
                    }
                    if (uiState.loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 顶栏弹层：源列表（5 功能入口）/ 最近使用 / 分类拖动排序
    when (activeSheet) {
        HomeSheet.SOURCE_LIST -> SourceListSheet(
            sources = sources,
            currentSourceId = viewModel.currentSourceId,
            lockedVisible = lockedVisible,
            posterLayout = posterLayout,
            onSelect = {
                viewModel.selectSource(it)
                activeSheet = null
            },
            onDismiss = { activeSheet = null },
            onCyclePosterLayout = viewModel::cyclePosterLayout,
            onOpenCategoryManager = { activeSheet = HomeSheet.CATEGORY_MANAGER },
            onOpenCloudTool = { app.showMessage("「云盘工具」暂未支持") },
            onOpenSourceManage = {
                activeSheet = null
                app.openSettingsPage(SettingsPage.VIDEO_SOURCE)
            },
            onLockClick = {
                // 解锁成功后源列表弹层保持打开，直接展示加锁源
                if (lockedVisible) {
                    viewModel.hideLockedSources()
                } else {
                    lockSetMode = !viewModel.hasLockPassword()
                }
            },
        )
        HomeSheet.HISTORY -> RecentSourcesSheet(
            // 加锁源默认隐藏：未解锁会话内不出现在最近使用里
            recentSources = recentSourceIds.mapNotNull { id -> sources.firstOrNull { it.id == id } }
                .filter { lockedVisible || !it.locked },
            currentSourceId = viewModel.currentSourceId,
            onSelect = {
                viewModel.selectSource(it)
                activeSheet = null
            },
            onDismiss = { activeSheet = null },
        )
        HomeSheet.CATEGORY_MANAGER -> CategoryManagerSheet(
            // 候选：排除已屏蔽分类（屏蔽在屏蔽页管理）
            categories = uiState.categories.filter { it.typeName !in blockedCategoryNames },
            hiddenCategoryIds = hiddenCategoryIds,
            onHide = { hiddenCategoryIds = hiddenCategoryIds + it },
            onShow = { hiddenCategoryIds = hiddenCategoryIds - it },
            onMove = viewModel::moveCategory,
            onOpenBlockedManager = {
                activeSheet = null
                // 首次点击直接进入屏蔽页（免设密码）；设置过密码后再次进入才需验证
                blockedPwdFlow = if (prefs.getString(PrefsKeys.KEY_BLOCKED_PASSWORD).isBlank()) {
                    null
                } else {
                    BlockedPwdFlow.VERIFY_UNLOCK
                }
                showBlockedManager = blockedPwdFlow == null
            },
            onDismiss = { activeSheet = null },
        )
        null -> Unit
    }

    // 加锁源密码框：首次设置 / 验证，成功后解锁显示锁列表
    lockSetMode?.let { isSetMode ->
        LockPasswordDialog(
            isSetMode = isSetMode,
            onSubmit = viewModel::unlockLockedSources,
            onDismiss = { lockSetMode = null },
        )
    }

    // 屏蔽分类密码框（图2）：验证进入 / 设置新密码 / 更新前验证旧密码；key 重建清空输入
    blockedPwdFlow?.let { flow ->
        key(flow) {
            BlockedPasswordDialog(
                title = when (flow) {
                    BlockedPwdFlow.VERIFY_UNLOCK -> "输入屏蔽密码"
                    BlockedPwdFlow.SET_NEW -> "设置屏蔽密码"
                    BlockedPwdFlow.VERIFY_OLD -> "输入原密码"
                },
                needConfirm = flow == BlockedPwdFlow.SET_NEW,
                errorText = blockedPwdError,
                onSubmit = { password ->
                    val stored = prefs.getString(PrefsKeys.KEY_BLOCKED_PASSWORD)
                    when (flow) {
                        BlockedPwdFlow.VERIFY_UNLOCK -> {
                            if (sha256Hex(password) == stored) {
                                blockedPwdFlow = null
                                blockedPwdError = null
                                showBlockedManager = true
                            } else {
                                blockedPwdError = "密码错误"
                            }
                        }
                        BlockedPwdFlow.SET_NEW -> {
                            if (password.isBlank()) {
                                blockedPwdError = "密码不能为空"
                            } else {
                                prefs.put(PrefsKeys.KEY_BLOCKED_PASSWORD, sha256Hex(password))
                                blockedPwdFlow = null
                                blockedPwdError = null
                                showBlockedManager = true
                            }
                        }
                        BlockedPwdFlow.VERIFY_OLD -> {
                            if (sha256Hex(password) == stored) {
                                blockedPwdFlow = BlockedPwdFlow.SET_NEW
                                blockedPwdError = null
                            } else {
                                blockedPwdError = "密码错误"
                            }
                        }
                    }
                },
                onDismiss = {
                    blockedPwdFlow = null
                    blockedPwdError = null
                },
            )
        }
    }

    // 屏蔽分类管理页（图2）：返回 + 标题 + 设置菜单（添加 / 设置/更新密码）
    if (showBlockedManager) {
        BlockedCategoryManagerOverlay(
            allCategories = uiState.categories,
            blockedCategoryNames = blockedCategoryNames,
            onToggleBlock = toggleBlockCategory,
            onChangePassword = {
                blockedPwdFlow = if (prefs.getString(PrefsKeys.KEY_BLOCKED_PASSWORD).isBlank()) {
                    BlockedPwdFlow.SET_NEW
                } else {
                    BlockedPwdFlow.VERIFY_OLD
                }
            },
            onDismiss = { showBlockedManager = false },
        )
    }
}

/** 顶部栏：历史源入口（左图标）+ 当前源标题（点击弹源列表）+ 导入/收藏/搜索快捷入口 */
@Composable
private fun HomeTopBar(
    app: AppViewModel,
    viewModel: HomeViewModel,
    sources: List<ApiSourceEntity>,
    onShowSheet: (HomeSheet) -> Unit,
) {
    val currentSourceId = viewModel.currentSourceId
    val currentSource = sources.firstOrNull { it.id == currentSourceId } ?: sources.firstOrNull()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 历史源入口：底部弹出最近使用过的源，快速切换
        IconButton(onClick = { onShowSheet(HomeSheet.HISTORY) }) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = "最近使用的源",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // 标题占满剩余空间：当前源圆点 + 名称，点击弹源列表
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onShowSheet(HomeSheet.SOURCE_LIST) }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = currentSource?.name ?: "添加源后点我切换",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { app.navigate(Screen.Settings) }) {
            Icon(Icons.Outlined.ContentPaste, contentDescription = "导入/管理源")
        }
        IconButton(onClick = { app.navigate(Screen.Favorites) }) {
            Icon(Icons.Outlined.Favorite, contentDescription = "收藏")
        }
        IconButton(onClick = { app.navigate(Screen.Search) }) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索")
        }
    }
}

/** 分类横向标签（选中带文本等宽下划线，与直播页一致） */
@Composable
private fun CategoryTabs(categories: List<Category>, selectedId: Int, onSelect: (Category) -> Unit) {
    val selectedIndex = categories.indexOfFirst { it.typeId == selectedId }.coerceAtLeast(0)
    TextWidthTabRow(
        tabNames = categories.map { it.typeName },
        selectedTabIndex = selectedIndex,
        onSelectTab = { onSelect(categories[it]) },
    )
}

/**
 * 屏蔽分类密码对话框：needConfirm=true 新密码+确认（设置）；false 单密码验证。
 * 校验由调用方完成并经 errorText 反馈错误。
 */
@Composable
private fun BlockedPasswordDialog(
    title: String,
    needConfirm: Boolean,
    errorText: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("密码") },
                    isError = errorText != null,
                )
                if (needConfirm) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("确认密码") },
                        isError = errorText != null,
                    )
                }
                if (errorText != null) {
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 屏蔽分类管理页（图2）：全屏覆盖层。顶栏 返回 + 标题 + 设置菜单（添加 / 设置/更新密码）；
 * 内容为已屏蔽分类列表（删除图标解除屏蔽）。进入本页已通过密码验证，页内操作免密。
 */
@Composable
private fun BlockedCategoryManagerOverlay(
    allCategories: List<Category>,
    blockedCategoryNames: Set<String>,
    onToggleBlock: (String) -> Unit,
    onChangePassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(8f),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "屏蔽分类",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("添加") },
                            onClick = {
                                menuExpanded = false
                                showAddDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("设置/更新密码") },
                            onClick = {
                                menuExpanded = false
                                onChangePassword()
                            },
                        )
                    }
                }
            }
            // 直接展示屏蔽名称集合：手动输入的分类即使不在当前源也要可见可解除
            val blockedNames = blockedCategoryNames.toList().sorted()
            if (blockedNames.isEmpty()) {
                EmptyView("暂无屏蔽分类，点右上角「添加」")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(blockedNames.size) { index ->
                        val name = blockedNames[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onToggleBlock(name) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "解除屏蔽",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showAddDialog) {
            AddBlockedCategoryDialog(
                candidates = allCategories.filter { it.typeName !in blockedCategoryNames },
                onSelect = { category ->
                    onToggleBlock(category.typeName)
                    showAddDialog = false
                },
                onAddName = { name ->
                    onToggleBlock(name)
                    showAddDialog = false
                },
                onDismiss = { showAddDialog = false },
            )
        }
    }
}

/** 添加屏蔽分类对话框：支持手动输入分类名（跨源生效）或从当前源分类列表选择 */
@Composable
private fun AddBlockedCategoryDialog(
    candidates: List<Category>,
    onSelect: (Category) -> Unit,
    onAddName: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加屏蔽分类", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        label = { Text("手动输入分类名") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (input.isNotBlank()) onAddName(input.trim()) },
                        enabled = input.isNotBlank(),
                    ) {
                        Text("添加")
                    }
                }
                Text(
                    text = "或从当前源分类选择",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(candidates.size) { index ->
                        val category = candidates[index]
                        Text(
                            text = category.typeName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(category) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
