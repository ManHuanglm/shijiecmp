package com.shiping.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiping.desktop.data.model.ApiSourceEntity
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.data.repository.SearchGroupData
import com.shiping.desktop.ui.common.EmptyView
import com.shiping.desktop.ui.common.VideoCard
import com.shiping.desktop.util.Constants
import com.shiping.shared.graph.AppGraph
import com.shiping.shared.prefs.PrefsKeys
import com.shiping.shared.util.ChineseTrans
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 搜索历史最多保留条数 */
private const val SEARCH_HISTORY_MAX = 10

/** 搜索历史偏好键（换行分隔多个关键词） */
private const val SEARCH_HISTORY_KEY = "search_history"

/** 聚合搜索状态：分组进度逐个回填；热搜词引导 + 搜索历史持久化 + 失败源重试 */
class SearchViewModel(
    private val scope: CoroutineScope,
    private val graph: AppGraph,
) {
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _groups = MutableStateFlow<List<SearchGroupData>>(emptyList())
    val groups: StateFlow<List<SearchGroupData>> = _groups.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** 已回填结果的源数量（聚合进度 X/Y 展示用） */
    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount.asStateFlow()

    /** 当前搜索的源实体缓存（loadMore 按 sourceId 取回传给仓库） */
    private var searchSources: List<ApiSourceEntity> = emptyList()

    /** 搜索历史（最近在前，最多 [SEARCH_HISTORY_MAX] 条，prefs 持久化跨会话保留） */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    init {
        _history.value = graph.prefs.getString(SEARCH_HISTORY_KEY)
            .split('\n')
            .filter { it.isNotBlank() }
            .take(SEARCH_HISTORY_MAX)
    }

    /** 发起聚合搜索：所有启用源并发查询，结果按完成顺序回填并累计进度 */
    fun search(text: String) {
        // 关键词繁转简（对齐 FongMi t2s）：简体输入零改动，繁体输入转简体提升多数源命中率
        val keyword = ChineseTrans.t2s(text.trim()) ?: ""
        if (keyword.isEmpty() || _searching.value) return
        _keyword.value = keyword
        _searching.value = true
        _completedCount.value = 0
        saveHistory(keyword)
        val enabledSources = graph.apiSourceRepository.sources.value.filter { it.enabled && it.searchable }
        searchSources = enabledSources
        // 先按发起顺序占位，结果完成后按 sourceId 回填
        _groups.value = enabledSources.map { SearchGroupData(it.id, it.name, it.url) }
        scope.launch {
            enabledSources.forEach { source ->
                launch {
                    fillGroup(graph.vodRepository.searchOneSource(source, keyword))
                    _completedCount.value++
                }
            }
            _searching.value = false
        }
    }

    /**
     * 触底加载更多：对仍可分页的源并发拉取下一页并原位追加。
     * [sourceId] 指定时仅加载该源（侧栏当前源模式），null=全部可分页源（混排模式）。
     * 新页无新增内容（源不支持翻页返回重复数据）时置 hasMore=false 自然终止。
     */
    fun loadMore(sourceId: Long? = null) {
        val keyword = _keyword.value
        if (keyword.isEmpty() || _searching.value) return
        val targets = _groups.value.filter {
            (sourceId == null || it.sourceId == sourceId) &&
                it.done && it.hasMore && !it.loadingMore && it.error == null && it.results.isNotEmpty()
        }
        if (targets.isEmpty()) return
        _groups.value = _groups.value.map { group ->
            if (targets.any { it.sourceId == group.sourceId }) group.copy(loadingMore = true) else group
        }
        targets.forEach { target ->
            scope.launch {
                val source = searchSources.firstOrNull { it.id == target.sourceId }
                val next = source?.let {
                    runCatching { graph.vodRepository.searchOneSource(it, keyword, page = target.page + 1) }
                }
                _groups.value = _groups.value.map { group ->
                    if (group.sourceId != target.sourceId) return@map group
                    when {
                        next == null || next.getOrNull()?.error != null ->
                            // 翻页失败：停止该源后续翻页（保留已加载内容，不打断列表）
                            group.copy(loadingMore = false, hasMore = false)
                        else -> {
                            val fetched = next.getOrThrow()
                            val seen = group.results.mapTo(HashSet()) { it.vodId }
                            val fresh = fetched.results.filter { seen.add(it.vodId) }
                            group.copy(
                                results = group.results + fresh,
                                page = fetched.page,
                                hasMore = fresh.isNotEmpty() && fetched.hasMore,
                                loadingMore = false,
                            )
                        }
                    }
                }
            }
        }
    }

    /** 重试单个失败源：组置回搜索中并重新查询，完成原位回填（不影响其他源） */
    fun retry(sourceId: Long) {
        val source = graph.apiSourceRepository.sources.value.firstOrNull { it.id == sourceId } ?: return
        val keyword = _keyword.value
        if (keyword.isEmpty()) return
        _groups.value = _groups.value.toMutableList().apply {
            val index = indexOfFirst { it.sourceId == sourceId }
            if (index >= 0) this[index] = SearchGroupData(source.id, source.name, source.url)
        }
        scope.launch { fillGroup(graph.vodRepository.searchOneSource(source, keyword)) }
    }

    /** 清空搜索历史（同步清除持久化） */
    fun clearHistory() {
        _history.value = emptyList()
        graph.prefs.put(SEARCH_HISTORY_KEY, null)
    }

    /** 结果原位回填（按 sourceId 匹配占位，无占位时追加） */
    private fun fillGroup(result: SearchGroupData) {
        _groups.value = _groups.value.toMutableList().apply {
            val insertAt = indexOfFirst { it.sourceId == result.sourceId }
            if (insertAt >= 0) this[insertAt] = result else add(result)
        }
    }

    /** 保存搜索词：去重置顶，超限截断，同步写入 prefs */
    private fun saveHistory(keyword: String) {
        _history.value = (listOf(keyword) + _history.value).distinct().take(SEARCH_HISTORY_MAX)
        graph.prefs.put(SEARCH_HISTORY_KEY, _history.value.joinToString("\n"))
    }
}

/** 搜索结果布局模式（结果区右上角按钮循环切换，prefs 持久化） */
enum class SearchDisplayMode(val label: String) {
    /** 分组行：每源一行横排（默认，含失败组重试与搜索中占位） */
    GROUPS("分组"),

    /** 源侧栏：左侧源列表（带结果数徽标）+ 右侧当前源海报网格 */
    SIDEBAR("侧栏"),

    /** 紧凑：顶部源条（名+计数）+ 下方全部结果混排网格 */
    COMPACT("混排"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(app: AppViewModel) {
    val graph = app.graph
    val scope = rememberCoroutineScope()
    val viewModel = remember { SearchViewModel(scope, graph) }
    val groups by viewModel.groups.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val completedCount by viewModel.completedCount.collectAsState()
    val history by viewModel.history.collectAsState()
    var input by remember { mutableStateOf("") }
    // 结果布局（持久化；循环切换）
    var displayMode by remember {
        val saved = graph.prefs.getString(PrefsKeys.KEY_SEARCH_DISPLAY_MODE).orEmpty()
        mutableStateOf(
            SearchDisplayMode.entries.firstOrNull { it.name.equals(saved, true) } ?: SearchDisplayMode.GROUPS,
        )
    }
    // 海报尺寸档位（最小/小/中/大，与首页共用 PosterLayout；持久化；循环切换，侧栏/混排网格共用）
    var posterLayout by remember {
        val saved = graph.prefs.getInt(PrefsKeys.KEY_SEARCH_POSTER_LAYOUT, 0)
        mutableStateOf(PosterLayout.entries.getOrElse(saved) { PosterLayout.MEDIUM })
    }

    /** 触发搜索（回车/按钮/历史/热搜共用）：同步输入框并搜索 */
    fun searchWord(word: String) {
        input = word
        viewModel.search(word)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入片名搜索全部启用源") },
                singleLine = true,
                shape = RoundedCornerShape(50),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清空")
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .onKeyEvent { event ->
                        // 回车触发搜索
                        if (event.key == Key.Enter) {
                            searchWord(input)
                            true
                        } else {
                            false
                        }
                    },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { searchWord(input) },
                enabled = !searching && input.isNotBlank(),
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (searching) "$completedCount/${groups.size} 源" else "搜索")
            }
        }

        // 搜索联想：聚合结果片名按出现频次聚合（多源同名的优先），点击仅填入搜索框
        val suggestions = remember(groups, input) {
            val keyword = input.trim()
            if (keyword.isEmpty()) {
                emptyList()
            } else {
                groups.asSequence()
                    .filter { it.error == null }
                    .flatMap { it.results.asSequence() }
                    .map { it.vodName.trim() }
                    .filter { it.isNotEmpty() && !it.equals(keyword, true) }
                    .groupBy { it }
                    .map { (name, list) -> name to list.size }
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
                    .take(12)
                    .map { it.first }
                    .toList()
            }
        }
        if (suggestions.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(suggestions, key = { it }) { word ->
                    Surface(
                        onClick = { input = word },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = word,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // 展示分组：同接口源合并（B 站等同爬虫多变体源返回相同内容，聚合为单组去重）
        val displayGroups = remember(groups) { mergeDuplicateGroups(groups) }
        // 仅保留有结果的源（无数据源三种布局均不显示；失败组仅在分组布局展示重试）
        val resultGroups = displayGroups.filter { it.error == null && it.results.isNotEmpty() }
        val showGuide = input.isEmpty() && groups.isEmpty() && !searching
        if (!showGuide) {
            // 顶行：搜索中显示聚合进度条，完成后显示结果摘要 + 布局切换按钮
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    LinearProgressIndicator(
                        progress = { if (groups.isEmpty()) 0f else completedCount.toFloat() / groups.size },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = "共 ${resultGroups.sumOf { it.results.size }} 个结果 · ${resultGroups.size} 源",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                IconButton(
                    onClick = {
                        posterLayout = PosterLayout.entries[
                            (posterLayout.ordinal + 1).mod(PosterLayout.entries.size),
                        ]
                        graph.prefs.put(PrefsKeys.KEY_SEARCH_POSTER_LAYOUT, posterLayout.ordinal)
                    },
                ) {
                    Icon(posterLayout.icon, contentDescription = "海报布局（当前：${posterLayout.label}）")
                }
                IconButton(
                    onClick = {
                        displayMode = SearchDisplayMode.entries[
                            (displayMode.ordinal + 1).mod(SearchDisplayMode.entries.size),
                        ]
                        graph.prefs.put(PrefsKeys.KEY_SEARCH_DISPLAY_MODE, displayMode.name)
                    },
                ) {
                    Icon(Icons.Outlined.GridView, contentDescription = "切换布局（当前：${displayMode.label}）")
                }
            }
        }
        when {
            showGuide -> {
                // 引导区：搜索历史（可清空）+ 热搜词
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    if (history.isNotEmpty()) {
                        item(key = "history_title") {
                            SectionTitle("搜索历史") {
                                TextButton(onClick = viewModel::clearHistory) { Text("清空") }
                            }
                        }
                        item(key = "history_chips") {
                            ChipFlow(history) { searchWord(it) }
                        }
                    }
                    item(key = "hot_title") {
                        SectionTitle("热门搜索")
                    }
                    item(key = "hot_chips") {
                        ChipFlow(Constants.RECOMMEND_SEARCHES) { searchWord(it) }
                    }
                }
            }
            else -> {
                // 结果区：按当前布局渲染
                when {
                    resultGroups.isEmpty() -> {
                        // 尚无已回填结果：搜索中显示分组占位流，完成则空态
                        if (searching) {
                            GroupResults(displayGroups, app) { sourceId -> viewModel.retry(sourceId) }
                        } else {
                            EmptyView(if (groups.isNotEmpty() && groups.all { it.error != null }) "搜索失败，请检查源配置或稍后重试" else "未找到相关内容")
                        }
                    }
                    displayMode == SearchDisplayMode.GROUPS ->
                        GroupResults(displayGroups, app) { sourceId -> viewModel.retry(sourceId) }
                    displayMode == SearchDisplayMode.SIDEBAR ->
                        SidebarResults(resultGroups, posterLayout, app) { sourceId ->
                            viewModel.loadMore(sourceId)
                        }
                    else ->
                        CompactResults(resultGroups, posterLayout, app) { viewModel.loadMore() }
                }
            }
        }
    }
}

/** 分组结果列表：每源一组标题 + 横排结果；未返回的源显示搜索中，失败组可重试；已返回但无数据的源不展示 */
@Composable
private fun GroupResults(
    groups: List<SearchGroupData>,
    app: AppViewModel,
    onRetry: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        groups.forEach { group ->
            when {
                group.error != null -> {
                    item(key = "err_${group.sourceId}") {
                        GroupHeader(group.sourceName, "失败：${group.error}") { onRetry(group.sourceId) }
                    }
                }
                group.results.isNotEmpty() -> {
                    item(key = "grp_${group.sourceId}") {
                        GroupHeader(group.sourceName, "${group.results.size} 个结果")
                    }
                    item(key = "row_${group.sourceId}") {
                        ResultRow(group.results, group.sourceUrl, app)
                    }
                }
                // 已返回且无数据：不显示该源（发起中/重试中的占位仍显示搜索中）
                !group.done -> {
                    item(key = "pend_${group.sourceId}") {
                        GroupHeader(group.sourceName, "搜索中…")
                    }
                }
            }
        }
    }
}

/** 区块标题（尾部可带操作，如「清空」） */
@Composable
private fun SectionTitle(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 标签流式排布（热搜词/搜索历史共用） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(words: List<String>, onPick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        words.forEach { word ->
            Surface(
                onClick = { onPick(word) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 分组标题行（失败时可重试该源） */
@Composable
private fun GroupHeader(name: String, status: String, onRetry: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onRetry).padding(4.dp),
            )
        }
    }
}

/** 单源结果横排 */
@Composable
private fun ResultRow(results: List<Vod>, sourceUrl: String, app: AppViewModel) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().height(200.dp),
    ) {
        items(results, key = { it.vodId }) { vod ->
            VideoCard(
                vod = vod,
                onClick = { app.push(Screen.Detail(vod.vodId, sourceUrl, vod.vodName, vod.safePic)) },
                modifier = Modifier.width(130.dp),
            )
        }
    }
}

/** 侧栏条目：全部组聚合跨源条目，每条携带各自来源地址供跳转详情 */
private class SidebarEntry(val key: String, val name: String, val items: List<Pair<Vod, String>>) {
    val badge: Int get() = items.size
}

/** 布局二：左侧源列表（「全部」置顶 + 名称+结果数徽标，选中高亮）+ 右侧当前条目海报网格（形态随 posterLayout，触底加载更多） */
@Composable
private fun SidebarResults(
    groups: List<SearchGroupData>,
    posterLayout: PosterLayout,
    app: AppViewModel,
    onLoadMore: (Long?) -> Unit,
) {
    // 「全部」置顶（对齐 FongMi）：跨源条目按组序拼接，其余条目为合并后的各源
    val entries = remember(groups) {
        buildList {
            add(SidebarEntry("all", "全部", groups.flatMap { g -> g.results.map { it to g.sourceUrl } }))
            groups.forEach { group ->
                add(SidebarEntry(group.sourceId.toString(), group.sourceName, group.results.map { it to group.sourceUrl }))
            }
        }
    }
    var selected by remember { mutableStateOf(0) }
    val current = entries[selected.coerceIn(0, entries.lastIndex)]
    // 当前条目的加载中状态：「全部」= 任意组翻页中；单源 = 该组翻页中
    val currentKey = current.key
    val currentLoadingMore = if (currentKey == "all") {
        groups.any { it.loadingMore }
    } else {
        groups.firstOrNull { it.sourceId.toString() == currentKey }?.loadingMore == true
    }
    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, current.items.size, currentKey) {
        if (shouldLoadMore) onLoadMore(currentKey.toLongOrNull())
    }
    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.width(150.dp).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                val picked = index == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = index }
                        .background(
                            if (picked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (picked) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${entry.badge}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 海报网格：卡片最小宽自动换列（不限制列，剩余空间均分，满宽无黑边）
        LazyVerticalGrid(
            columns = GridCells.Adaptive(posterLayout.minCellWidth),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(10.dp, 0.dp, 12.dp, 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(current.items, key = { "${current.key}_${it.first.vodId}_${it.first.vodName}" }) { (vod, sourceUrl) ->
                VideoCard(
                    vod = vod,
                    portraitRatio = posterLayout.aspectRatio,
                    onClick = { app.push(Screen.Detail(vod.vodId, sourceUrl, vod.vodName, vod.safePic)) },
                )
            }
            if (currentLoadingMore) {
                item(key = "sidebar_loading", contentType = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.height(22.dp)) }
                }
            }
        }
    }
}

/**
 * 同接口源合并：B 站等同爬虫多变体源（sourceUrl 相同）搜索返回相同内容，
 * 聚合为单组去重展示（名称取首个源名并标注「等 N 源」）；占位/失败/空组原样保留，组间顺序不变。
 */
private fun mergeDuplicateGroups(groups: List<SearchGroupData>): List<SearchGroupData> {
    val merged = mutableListOf<SearchGroupData>()
    val anchorIndexOf = HashMap<String, Int>()
    val headNameOf = HashMap<String, String>()
    val memberCountOf = HashMap<String, Int>()
    for (group in groups) {
        val mergeable = group.error == null && group.results.isNotEmpty() && group.sourceUrl.isNotEmpty()
        val anchor = if (mergeable) anchorIndexOf[group.sourceUrl] else null
        if (anchor == null) {
            if (mergeable) {
                anchorIndexOf[group.sourceUrl] = merged.size
                headNameOf[group.sourceUrl] = group.sourceName
                memberCountOf[group.sourceUrl] = 1
            }
            merged += group
            continue
        }
        val head = merged[anchor]
        val seen = head.results.mapTo(HashSet()) { it.vodId to it.vodName }
        val extra = group.results.filter { seen.add(it.vodId to it.vodName) }
        val count = (memberCountOf[group.sourceUrl] ?: 1) + 1
        memberCountOf[group.sourceUrl] = count
        merged[anchor] = head.copy(
            sourceName = "${headNameOf[group.sourceUrl]} 等${count}源",
            results = head.results + extra,
            hasMore = false,
        )
    }
    return merged
}

/** 布局三：顶部源条（名+计数，点击过滤该源）+ 下方全部结果混排网格（形态随 posterLayout，触底加载更多） */
@Composable
private fun CompactResults(
    groups: List<SearchGroupData>,
    posterLayout: PosterLayout,
    app: AppViewModel,
    onLoadMore: () -> Unit,
) {
    // 选中的源过滤（null=全部混排）；跨源 vodId 可能重复，网格 key 拼源 ID
    var filterSourceId by remember { mutableStateOf<Long?>(null) }
    val visibleGroups = groups.filter { filterSourceId == null || it.sourceId == filterSourceId }
    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, visibleGroups.sumOf { it.results.size }) {
        if (shouldLoadMore) onLoadMore()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(groups, key = { it.sourceId }) { group ->
                val picked = filterSourceId == group.sourceId
                Surface(
                    onClick = { filterSourceId = if (picked) null else group.sourceId },
                    shape = RoundedCornerShape(50),
                    color = if (picked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = "${group.sourceName} (${group.results.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (picked) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 海报网格：卡片最小宽自动换列（不限制列，剩余空间均分，满宽无黑边）
        LazyVerticalGrid(
            columns = GridCells.Adaptive(posterLayout.minCellWidth),
            state = gridState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            visibleGroups.forEach { group ->
                items(
                    group.results,
                    key = { "${group.sourceId}_${it.vodId}" },
                ) { vod ->
                    VideoCard(
                        vod = vod,
                        portraitRatio = posterLayout.aspectRatio,
                        onClick = {
                            app.push(Screen.Detail(vod.vodId, group.sourceUrl, vod.vodName, vod.safePic))
                        },
                    )
                }
            }
            if (visibleGroups.any { it.loadingMore }) {
                item(key = "compact_loading", contentType = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.height(22.dp)) }
                }
            }
        }
    }
}
