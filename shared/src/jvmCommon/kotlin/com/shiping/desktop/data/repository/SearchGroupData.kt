package com.shiping.desktop.data.repository

import com.shiping.desktop.data.model.Vod

/** 聚合搜索结果分组（按 API 源）。定义于 jvmCommon：仓库接口需引用 */
data class SearchGroupData(
    val sourceId: Long,
    val sourceName: String,
    val sourceUrl: String = "",
    val results: List<Vod> = emptyList(),
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true,
)
