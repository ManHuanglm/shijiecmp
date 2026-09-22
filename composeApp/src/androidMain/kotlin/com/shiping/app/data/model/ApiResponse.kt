package com.shiping.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * MacCMS 通用响应包装
 */
data class ApiResponse<T>(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("msg") val msg: String = "",
    @SerializedName("page") val page: Int = 1,
    @SerializedName("pagecount") val pageCount: Int = 0,
    @SerializedName("limit") val limit: Int = 20,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("list") val list: List<T> = emptyList(),
    @SerializedName("class") val classList: List<Category> = emptyList()
)

/**
 * 分类
 */
data class Category(
    @SerializedName("type_id") val typeId: Int = 0,
    @SerializedName("type_name") val typeName: String = ""
)
