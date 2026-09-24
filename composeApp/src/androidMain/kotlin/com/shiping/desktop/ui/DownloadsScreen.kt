package com.shiping.desktop.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiping.app.ShipingApp
import com.shiping.app.data.download.DownloadEngine
import com.shiping.app.data.local.AppDatabase
import com.shiping.app.data.model.DownloadEntity
import com.shiping.app.DirectPlaybackActivity

/**
 * 下载管理页（Android 专属）：任务队列展示 + 暂停/恢复/重试/删除/本地播放。
 * 数据源 Room downloads 表实时流；动作经 DownloadEngine（队列自动唤醒）。
 */
@Composable
fun DownloadsScreen(app: AppViewModel) {
    val downloads by AppDatabase.getInstance(ShipingApp.context).downloadDao()
        .observeAll().collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { app.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("下载管理", style = MaterialTheme.typography.titleMedium)
        }
        if (downloads.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无下载任务", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("详情页播放中打开选集面板即可下载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(downloads, key = { it.contentId }) { task ->
                    DownloadTaskCard(task)
                }
            }
        }
    }
}

/** 单个下载任务卡片：标题/状态/进度条/字节与速度/操作排 */
@Composable
private fun DownloadTaskCard(task: DownloadEntity) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (task.status) {
                DownloadEntity.STATUS_DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { task.progress / 100f },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    )
                    Text(
                        text = buildString {
                            append("下载中 ${task.progress}%")
                            if (task.totalBytes > 0) append(" · ${formatBytes(task.bytesDownloaded)}/${formatBytes(task.totalBytes)}")
                            if (task.speedBytesPerSecond > 0) append(" · ${formatBytes(task.speedBytesPerSecond)}/s")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadEntity.STATUS_QUEUED -> StatusText("排队中")
                DownloadEntity.STATUS_PAUSED -> StatusText(
                    if (task.progress in 1..99) "已暂停（续传将从头下载）" else "已暂停",
                )
                DownloadEntity.STATUS_FAILED -> StatusText("失败：${task.errorMessage ?: "未知错误"}")
                DownloadEntity.STATUS_COMPLETED -> StatusText("已下载 · ${formatBytes(task.bytesDownloaded)} · 相册「视界」可见")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (task.status == DownloadEntity.STATUS_DOWNLOADING || task.status == DownloadEntity.STATUS_QUEUED) {
                    ActionIcon(Icons.Filled.Pause, "暂停") { DownloadEngine.pause(task.contentId) }
                }
                if (task.status == DownloadEntity.STATUS_PAUSED) {
                    ActionIcon(Icons.Filled.PlayArrow, "继续") { DownloadEngine.resume(task.contentId) }
                }
                if (task.status == DownloadEntity.STATUS_FAILED) {
                    ActionIcon(Icons.Filled.Refresh, "重试") { DownloadEngine.resume(task.contentId) }
                }
                if (task.status == DownloadEntity.STATUS_COMPLETED && task.localPath.isNotBlank()) {
                    ActionIcon(Icons.Filled.PlayArrow, "播放本地文件") { playLocalFile(task.localPath) }
                }
                if (task.status != DownloadEntity.STATUS_DOWNLOADING) {
                    ActionIcon(Icons.Filled.Delete, "删除") { DownloadEngine.delete(task.contentId) }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 播放相册本地文件（DirectPlaybackActivity 复用，media3 原生支持 content://） */
private fun playLocalFile(localPath: String) {
    runCatching {
        ShipingApp.context.startActivity(
            Intent(ShipingApp.context, DirectPlaybackActivity::class.java).apply {
                putExtra(DirectPlaybackActivity.EXTRA_URL, localPath)
                putExtra(DirectPlaybackActivity.EXTRA_TITLE, "本地播放")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/** 字节格式化（B/KB/MB/GB） */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1fKB".format(kb)
    val mb = kb / 1024
    if (mb < 1024) return "%.1fMB".format(mb)
    return "%.2fGB".format(mb / 1024)
}
