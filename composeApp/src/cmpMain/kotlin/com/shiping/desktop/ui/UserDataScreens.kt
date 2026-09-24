package com.shiping.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiping.desktop.data.model.FavoriteEntity
import com.shiping.desktop.data.model.PlayHistoryEntity
import com.shiping.desktop.ui.common.ConfirmDialog
import com.shiping.desktop.ui.common.EmptyView
import com.shiping.desktop.ui.common.VideoCard
import com.shiping.desktop.ui.common.isEpisodeDownloadSupported
import kotlinx.coroutines.launch

/** 播放历史页：卡片网格 + 单删/清空，点击继续播放 */
@Composable
fun HistoryScreen(app: AppViewModel) {
    val graph = app.graph
    val scope = rememberCoroutineScope()
    val history by graph.playHistoryRepository.history.collectAsState()
    var pendingClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PlayHistoryEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("播放历史", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (isEpisodeDownloadSupported) {
                IconButton(onClick = { app.push(Screen.Downloads) }) {
                    Icon(Icons.Filled.Download, contentDescription = "下载管理")
                }
            }
            if (history.isNotEmpty()) {
                TextButton(onClick = { pendingClear = true }) { Text("清空") }
            }
        }
        if (history.isEmpty()) {
            EmptyView("暂无播放记录")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count = history.size, key = { history[it].let { h -> "${h.sourceUrl}_${h.vodId}" } }) { index ->
                    val record = history[index]
                    VideoCard(
                        vod = com.shiping.desktop.data.model.Vod(
                            vodId = record.vodId,
                            vodName = record.title,
                            vodPic = record.vodPic,
                            vodRemarks = formatHistoryRemark(record),
                        ),
                        onClick = { app.push(Screen.Detail(record.vodId, record.sourceUrl, record.title, record.vodPic)) },
                        footer = {
                            Text(
                                text = "看过：${record.lastEpisodeName.ifBlank { "未知剧集" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
    if (pendingClear) {
        ConfirmDialog(title = "清空播放历史", message = "将删除全部播放记录，确定吗？", onConfirm = {
            scope.launch { graph.playHistoryRepository.clear() }
        }, onDismiss = { pendingClear = false })
    }
    pendingDelete?.let { record ->
        ConfirmDialog(title = "删除记录", message = "删除「${record.title}」的播放记录？", onConfirm = {
            scope.launch { graph.playHistoryRepository.delete(record.vodId, record.sourceUrl) }
        }, onDismiss = { pendingDelete = null })
    }
}

/** 历史卡片角标：进度（有时长时显示百分比） */
private fun formatHistoryRemark(record: PlayHistoryEntity): String =
    if (record.durationMs > 0) {
        "${(record.lastPositionMs * 100 / record.durationMs).coerceIn(0, 100)}%"
    } else {
        record.lastEpisodeName
    }

/** 收藏页：卡片网格 + 取消收藏 */
@Composable
fun FavoriteScreen(app: AppViewModel) {
    val graph = app.graph
    val scope = rememberCoroutineScope()
    val favorites by graph.favoriteRepository.favorites.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("我的收藏", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("${favorites.size} 部", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (favorites.isEmpty()) {
            EmptyView("暂无收藏，去详情页添加")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count = favorites.size, key = { favorites[it].let { f -> "${f.sourceUrl}_${f.vodId}" } }) { index ->
                    val favorite = favorites[index]
                    FavoriteCard(favorite = favorite, onOpen = {
                        app.push(Screen.Detail(favorite.vodId, favorite.sourceUrl, favorite.title, favorite.vodPic))
                    }, onRemove = {
                        scope.launch { graph.favoriteRepository.remove(favorite.vodId, favorite.sourceUrl) }
                    })
                }
            }
        }
    }
}

/** 收藏卡片（右上角删除按钮） */
@Composable
private fun FavoriteCard(favorite: FavoriteEntity, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box {
        VideoCard(
            vod = com.shiping.desktop.data.model.Vod(vodId = favorite.vodId, vodName = favorite.title, vodPic = favorite.vodPic),
            onClick = onOpen,
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "取消收藏",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
