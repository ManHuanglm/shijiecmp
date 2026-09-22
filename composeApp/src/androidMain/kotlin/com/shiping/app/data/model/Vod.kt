package com.shiping.app.data.model

import com.google.gson.annotations.SerializedName
import com.shiping.app.util.Constants

/**
 * 视频数据模型，同时兼容列表接口与详情接口返回字段
 */
data class Vod(
    @SerializedName("vod_id") val vodId: Int = 0,
    @SerializedName("type_id") val typeId: Int = 0,
    @SerializedName("type_id_1") val typeId1: Int = 0,
    @SerializedName("vod_name") val vodName: String = "",
    @SerializedName("vod_sub") val vodSub: String = "",
    @SerializedName("vod_en") val vodEn: String = "",
    @SerializedName("vod_status") val vodStatus: Int = 0,
    @SerializedName("vod_letter") val vodLetter: String = "",
    @SerializedName("vod_tag") val vodTag: String = "",
    @SerializedName("vod_class") val vodClass: String = "",
    @SerializedName("vod_pic") val vodPic: String = "",
    @SerializedName("vod_pic_thumb") val vodPicThumb: String = "",
    @SerializedName("vod_pic_slide") val vodPicSlide: String = "",
    @SerializedName("vod_actor") val vodActor: String = "",
    @SerializedName("vod_director") val vodDirector: String = "",
    @SerializedName("vod_writer") val vodWriter: String = "",
    @SerializedName("vod_behind") val vodBehind: String = "",
    @SerializedName("vod_blurb") val vodBlurb: String = "",
    @SerializedName("vod_remarks") val vodRemarks: String = "",
    @SerializedName("vod_pubdate") val vodPubdate: String = "",
    @SerializedName("vod_total") val vodTotal: Int = 0,
    @SerializedName("vod_serial") val vodSerial: String = "",
    @SerializedName("vod_tv") val vodTv: String = "",
    @SerializedName("vod_weekday") val vodWeekday: String = "",
    @SerializedName("vod_area") val vodArea: String = "",
    @SerializedName("vod_lang") val vodLang: String = "",
    @SerializedName("vod_year") val vodYear: String = "",
    @SerializedName("vod_version") val vodVersion: String = "",
    @SerializedName("vod_state") val vodState: String = "",
    @SerializedName("vod_author") val vodAuthor: String = "",
    @SerializedName("vod_isend") val vodIsend: Int = 0,
    @SerializedName("vod_hits") val vodHits: Int = 0,
    @SerializedName("vod_duration") val vodDuration: String = "",
    @SerializedName("vod_score") val vodScore: String = "",
    @SerializedName("vod_time") val vodTime: String = "",
    @SerializedName("vod_content") val vodContent: String = "",
    @SerializedName("vod_play_from") val vodPlayFrom: String = "",
    @SerializedName("vod_play_server") val vodPlayServer: String = "",
    @SerializedName("vod_play_note") val vodPlayNote: String = "",
    @SerializedName("vod_play_url") val vodPlayUrl: String = "",
    @SerializedName("type_name") val typeName: String = "",
) {
    /**
     * 解析播放源列表。
     * 格式: vod_play_from = "source1$$$source2"
     *       vod_play_url  = "ep1$url1#ep2$url2$$$ep1$url1"
     */
    fun parsePlaySources(): List<PlaySource> {
        val fromList = vodPlayFrom.split(Constants.SEPARATOR_SOURCE).filter { it.isNotBlank() }
        val urlList = vodPlayUrl.split(Constants.SEPARATOR_SOURCE)
        return fromList.mapIndexed { index, sourceName ->
            val episodeStr = urlList.getOrNull(index).orEmpty()
            val episodes = episodeStr.split(Constants.SEPARATOR_EPISODE)
                .filter { it.contains(Constants.SEPARATOR_NAME_URL) }
                .mapNotNull { ep ->
                    val parts = ep.split(Constants.SEPARATOR_NAME_URL, limit = 2)
                    if (parts.size == 2) {
                        PlayEpisode(parts[0].trim(), parts[1].trim())
                    } else {
                        null
                    }
                }
            PlaySource(sourceName.trim(), episodes)
        }.filter { it.episodes.isNotEmpty() }
    }

    /** 优先取非空封面图 */
    val safePic: String
        get() = when {
            vodPic.isNotBlank() -> vodPic
            vodPicThumb.isNotBlank() -> vodPicThumb
            vodPicSlide.isNotBlank() -> vodPicSlide
            else -> ""
        }

    /**
     * 用详情数据补全列表项缺失的封面/评分等字段。
     * 列表接口(ac=list)通常不带 vod_pic，需要通过详情接口补全。
     */
    fun mergeWithDetail(detail: Vod): Vod = copy(
        vodPic = detail.vodPic,
        vodPicThumb = detail.vodPicThumb,
        vodPicSlide = detail.vodPicSlide,
        vodScore = detail.vodScore,
        vodRemarks = detail.vodRemarks.ifBlank { vodRemarks },
    )
}

/** 播放源 */
data class PlaySource(
    val name: String,
    val episodes: List<PlayEpisode>,
)

/** 单集 */
data class PlayEpisode(
    val name: String,
    val url: String,
)
