package com.shiping.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import com.shiping.desktop.data.model.Vod
import com.shiping.desktop.ui.common.EmptyView
import com.shiping.desktop.ui.common.VideoCard
import com.shiping.shared.graph.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 聚合搜索状态：分组进度逐个回填 */
class SearchViewModel(
    private val scope: CoroutineScope,
    private val graph: AppGraph,
) {
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _groups = MutableStateFlow<List<com.shiping.desktop.data.repository.SearchGroupData>>(emptyList())
    val groups: StateFlow<List<com.shiping.desktop.data.repository.SearchGroupData>> = _groups.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** 发起聚合搜索：所有启用源并发查询，结果按完成顺序回填 */
    fun search(text: String) {
        val keyword = text.trim()
        if (keyword.isEmpty() || _searching.value) return
        _keyword.value = keyword
        _searching.value = true
        val enabledSources = graph.apiSourceRepository.sources.value.filter { it.enabled && it.searchable }
        // 先按发起顺序占位，结果完成后按 sourceId 回填
        _groups.value = enabledSources.map {
            com.shiping.desktop.data.repository.SearchGroupData(it.id, it.name, it.url)
        }
        scope.launch {
            enabledSources.forEach { source ->
                launch {
                    val result = graph.vodRepository.searchOneSource(source, keyword)
                    _groups.value = _groups.value.toMutableList().apply {
                        val insertAt = indexOfFirst { it.sourceId == source.id }
                        if (insertAt >= 0) this[insertAt] = result else add(result)
                    }
                }
            }
            _searching.value = false
        }
    }
}

/** 搜索页：关键词输入 + 按源分组的聚合结果 */
@Composable
fun SearchScreen(app: AppViewModel) {
    val graph = app.graph
    val scope = rememberCoroutineScope()
    val viewModel = remember { SearchViewModel(scope, graph) }
    val groups by viewModel.groups.collectAsState()
    val searching by viewModel.searching.collectAsState()
    var input by remember { mutableStateOf("") }

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
                modifier = Modifier
                    .weight(1f)
                    .onKeyEvent { event ->
                        // 回车触发搜索
                        if (event.key == Key.Enter) {
                            viewModel.search(input)
                            true
                        } else {
                            false
                        }
                    },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.search(input) },
                enabled = !searching && input.isNotBlank(),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (searching) "搜索中" else "搜索")
            }
        }
        if (!searching && groups.all { it.results.isEmpty() && it.error == null }) {
            EmptyView("输入关键词开始搜索")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                groups.forEach { group ->
                    if (group.error != null) {
                        item(key = "err_${group.sourceId}") {
                            GroupHeader(group.sourceName, "失败：${group.error}")
                        }
                    } else if (group.results.isNotEmpty()) {
                        item(key = "grp_${group.sourceId}") {
                            GroupHeader(group.sourceName, "${group.results.size} 个结果")
                        }
                        item(key = "row_${group.sourceId}") {
                            ResultRow(group.results, group.sourceUrl, app)
                        }
                    } else if (searching) {
                        item(key = "pend_${group.sourceId}") {
                            GroupHeader(group.sourceName, "搜索中…")
                        }
                    }
                }
            }
        }
    }
}

/** 分组标题行 */
@Composable
private fun GroupHeader(name: String, status: String) {
    Text(
        text = "$name · $status",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
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
