package com.shiping.shared.download

/** 下载任务状态常量（双端引擎与下载管理页共用，取值与历史版本兼容） */
object DownloadStatus {
    const val QUEUED = 0
    const val DOWNLOADING = 1
    const val COMPLETED = 2
    const val FAILED = 3
    const val PAUSED = 4
}
