package com.shiping.app.data.remote

import com.shiping.app.data.model.ApiResponse
import com.shiping.app.data.model.Vod
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * MacCMS 视频接口
 */
interface ApiService {

    /**
     * 获取首页视频列表（带分类）
     * @param baseUrl 接口基地址
     * @param ac 动作类型，默认 list
     * @param page 页码
     * @param typeId 分类ID，0为全部
     * @param keyword 搜索关键词
     */
    @GET
    suspend fun getVodList(
        @Url baseUrl: String,
        @Query("ac") ac: String = "list",
        @Query("pg") page: Int = 1,
        @Query("t") typeId: Int = 0,
        @Query("wd") keyword: String? = null
    ): ApiResponse<Vod>

    /**
     * 获取视频详情
     * @param ids 视频ID，多个用逗号分隔
     */
    @GET
    suspend fun getVodDetail(
        @Url baseUrl: String,
        @Query("ac") ac: String = "detail",
        @Query("ids") ids: String
    ): ApiResponse<Vod>
}
