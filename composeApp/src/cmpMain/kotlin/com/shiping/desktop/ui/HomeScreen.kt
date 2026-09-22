package com.shiping.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.Category
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.ui.common.DropdownSelector
import com.shiping.desktop.ui.common.EmptyView
import com.shiping.desktop.ui.common.ErrorView
import com.shiping.desktop.ui.common.LoadingView
import com.shiping.desktop.ui.common.VideoCard
import com.shiping.shared.graph.AppGraph
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
    val error: String? = null,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: Int = 0,
    val items: List<Vod> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val currentSourceUrl: String = "",
)

/** 首页状态管理：源切换、分类筛选、分页加载 */
class HomeViewModel(
    private val scope: CoroutineScope,
    private val graph: AppGraph,
) {
    private val vodRepository = graph.vodRepository
    private val apiRepository = graph.apiSourceRepository

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val sources: StateFlow<List<ApiSourceEntity>> = apiRepository.sources

    /** 当前源 ID（持久化值） */
    val currentSourceId: Long? get() = apiRepository.currentApiId

    init {
        selectSource(apiRepository.getCurrentApiSource(), silent = true)
    }

    /** 切换源（并持久化）；silent=true 用于初始化首拉不提示 */
    fun selectSource(source: ApiSourceEntity?, silent: Boolean = false) {
        if (source != null && !silent) apiRepository.setCurrentApiId(source.id)
        _uiState.update { it.copy(categories = emptyList(), selectedCategoryId = 0, items = emptyList(), page = 1, pageCount = 1) }
        load(page = 1, append = false)
    }

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

    private fun load(page: Int, append: Boolean) {
        scope.launch {
            _uiState.update { it.copy(loading = !append, loadingMore = append, error = null) }
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
                        items = if (append) state.items + fresh else response.list,
                        page = page,
                        pageCount = pageCount.coerceAtLeast(1),
                        categories = response.classList.ifEmpty { state.categories },
                        currentSourceUrl = source?.url.orEmpty(),
                        error = if (!append && response.list.isEmpty()) "暂无内容" else null,
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败") }
            }
        }
    }
}

/** 首页：顶栏（源下拉切换 + 快捷导航）+ 分类标签 + 视频网格（触底自动翻页） */
@Composable
fun HomeScreen(app: AppViewModel) {
    val graph = app.graph
    val scope = rememberCoroutineScope()
    val viewModel = remember { HomeViewModel(scope, graph) }

    val uiState by viewModel.uiState.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val gridState = rememberLazyGridState()

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
        HomeTopBar(app = app, viewModel = viewModel, sources = sources)
        if (uiState.categories.isNotEmpty()) {
            CategoryTabs(
                categories = uiState.categories,
                selectedId = uiState.selectedCategoryId,
                onSelect = viewModel::selectCategory,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                uiState.loading -> LoadingView()
                uiState.error != null -> ErrorView(message = uiState.error!!, onRetry = { viewModel.selectSource(null, silent = true) })
                uiState.items.isEmpty() -> EmptyView("该源暂无数据")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
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
                            onClick = { app.push(Screen.Detail(vod.vodId, uiState.currentSourceUrl, vod.vodName, vod.safePic)) },
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
}

/** 顶部栏：应用图标 + 源下拉切换（与直播页一致）+ 导入/收藏/搜索快捷入口 */
@Composable
private fun HomeTopBar(
    app: AppViewModel,
    viewModel: HomeViewModel,
    sources: List<ApiSourceEntity>,
) {
    val currentSourceId = viewModel.currentSourceId
    val currentSource = sources.firstOrNull { it.id == currentSourceId } ?: sources.firstOrNull()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.LiveTv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        DropdownSelector(
            currentText = currentSource?.name ?: "添加源后点我切换",
            items = sources,
            itemText = { it.name },
            onSelect = { viewModel.selectSource(it) },
            modifier = Modifier.width(200.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { app.navigate(Screen.Settings) }) {
            Icon(Icons.Filled.ContentPaste, contentDescription = "导入/管理源")
        }
        IconButton(onClick = { app.navigate(Screen.Favorites) }) {
            Icon(Icons.Filled.FavoriteBorder, contentDescription = "收藏")
        }
        IconButton(onClick = { app.navigate(Screen.Search) }) {
            Icon(Icons.Filled.Search, contentDescription = "搜索")
        }
    }
}

/** 分类横向标签（选中带下划线，与直播页一致） */
@Composable
private fun CategoryTabs(categories: List<Category>, selectedId: Int, onSelect: (Category) -> Unit) {
    val selectedIndex = categories.indexOfFirst { it.typeId == selectedId }.coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 16.dp,
        containerColor = Color.Transparent,
    ) {
        categories.forEachIndexed { index, category ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(category) },
                text = {
                    Text(
                        text = category.typeName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
