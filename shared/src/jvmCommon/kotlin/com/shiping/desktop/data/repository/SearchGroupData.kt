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
    /** 该源查询是否已返回：false=占位未完成；返回后仍无数据则不展示该分组 */
    val done: Boolean = false,
    /** 该源翻页加载中（触底加载更多，防重复触发） */
    val loadingMore: Boolean = false,
)
