package com.shiping.app.data.model

/**
 * 电视频道（由 M3U 播放列表解析得到，同名频道合并为一条、保留全部线路）
 */
data class TvChannel(
    /** 频道名称，如 "CCTV-1 综合" */
    val name: String,
    /** 播放地址列表（同名频道多线路，第一个为默认线路） */
    val urls: List<String>,
    /** 分组名称，如 "央视"、"卫视" */
    val group: String,
    /** 台标地址，可为空 */
    val logo: String = "",
) {
    /** 当前默认播放地址 */
    val url: String get() = urls.first()

    /** 收藏键（名称+首线路地址，跨直播源稳定去重） */
    val favoriteKey: String get() = "$name|$url"
}
