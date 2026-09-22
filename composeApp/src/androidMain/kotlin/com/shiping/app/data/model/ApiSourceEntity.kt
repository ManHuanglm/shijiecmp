package com.shiping.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户自定义的视频 API 源
 */
@Entity(tableName = "api_sources")
data class ApiSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 名称 */
    val name: String,
    /** 接口地址（爬虫源为 key，如 csp_XXX） */
    val url: String,
    /** 备注/说明 */
    val note: String = "",
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 排序 */
    val sortOrder: Int = 0,
    /** 站点类型：0=CMS 接口，3=TVBox 爬虫源 */
    @ColumnInfo(defaultValue = "0") val siteType: Int = 0,
    /** 爬虫 jar 地址（导入时已展开全局 spider，保留分号分隔的备用地址/md5 校验段） */
    @ColumnInfo(defaultValue = "") val jarUrl: String = "",
    /** 爬虫类 key（如 csp_XXX），仅爬虫源使用 */
    @ColumnInfo(defaultValue = "") val spiderKey: String = "",
    /** 爬虫站点 ext 配置（对象/数组配置序列化为 JSON 字符串，传给 spider.init 的 extend） */
    @ColumnInfo(defaultValue = "") val ext: String = "",
    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis()
)
