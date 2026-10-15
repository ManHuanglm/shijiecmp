package com.shiping.desktop.ui

import androidx.compose.runtime.Composable

/**
 * 下载管理页（Android 壳）：复用 cmpMain 共享下载管理页（任务队列/操作/限速选择），
 * 数据与动作经 DownloadPlatform actual 接 DownloadEngine。
 *
 * 返回箭头仅在栈内 push 进入时展示（如「我的」页跳转，back 弹栈回上一页）；
 * 底部 Tab 进入时栈只有单层，back 无处可退，不展示返回箭头（切 Tab 离开）。
 */
@Composable
fun DownloadsScreen(app: AppViewModel) = DownloadsPageContent(
    app,
    onBack = if (app.navStack.value.size > 1) ({ app.back() }) else null,
)
