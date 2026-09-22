package com.shiping.desktop.data.spider

/**
 * 爬虫播放信息（ SpiderMapper.playerContent JSON 解析产物）。
 * 定义于 jvmCommon：仓库接口（com.shiping.shared.repository）需引用，
 * jvmMain 的 SpiderMapper 保持原包名引用无感。
 */
data class SpiderPlayInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val parse: Int = 0,
    /** B 站直链可用清晰度（非 B 站源为空） */
    val qualityOptions: List<BiliQualityOption> = emptyList(),
    /** B 站协议解析出的 cid，清晰度切换换取新直链用（非 B 站源为 0） */
    val biliCid: Long = 0L,
    /** B 站协议解析出的 aid，清晰度切换换取新直链用（非 B 站源为 0） */
    val biliAid: Long = 0L,
)

/** B 站直链清晰度选项：qn 为 B 站画质代号，label 为展示名（如「清晰 480P」） */
data class BiliQualityOption(
    val qn: Int,
    val label: String,
)
