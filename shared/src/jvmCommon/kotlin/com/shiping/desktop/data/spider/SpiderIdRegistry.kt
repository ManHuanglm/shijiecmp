package com.shiping.desktop.data.spider

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * 爬虫源 vod_id 映射：TVBox 爬虫的 vod_id 是任意字符串，而应用内 Vod.vodId 为 Int
 * 且贯穿详情路由/收藏/播放历史，这里用稳定哈希（CRC32，混入源地址避免跨源撞号）做双向映射。
 *
 * 说明：映射仅保存在内存（进程存活期内有效），极小概率哈希冲突按同源同条目处理；
 * 进程重启后未命中的详情请求会以 Int 值字符串兜底还原，通常重新进列表即可恢复映射。
 */
object SpiderIdRegistry {

    /** intKey -> realId，键为 "源地址|intKey" */
    private val realIds = ConcurrentHashMap<String, String>()

    /** 真实 id -> Int 稳定键，并同步登记反查映射 */
    fun toIntKey(sourceUrl: String, realId: String): Int {
        val crc = CRC32()
        crc.update(buildKey(sourceUrl, realId).toByteArray(Charsets.UTF_8))
        val intKey = crc.value.toInt()
        realIds[cacheKey(sourceUrl, intKey)] = realId
        return intKey
    }

    /** 由 Int 键还原真实 id；未登记时返回 null，由调用方兜底 */
    fun toRealId(sourceUrl: String, intKey: Int): String? = realIds[cacheKey(sourceUrl, intKey)]

    private fun buildKey(sourceUrl: String, realId: String): String = sourceUrl.trim() + "|" + realId

    private fun cacheKey(sourceUrl: String, intKey: Int): String = sourceUrl.trim() + "|" + intKey
}
