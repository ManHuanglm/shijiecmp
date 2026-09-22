package com.github.catvod.bean

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/** 线路/集名分隔符（与 TVBox vod 结构约定一致） */
private const val SEPARATOR_SOURCE = "\$\$\$"
private const val SEPARATOR_EPISODE = "#"
private const val SEPARATOR_NAME_URL = "$"

/**
 * 爬虫结果 bean（仿 FongMi/TV spider 库）：jar 内通过 fluent API 组装，
 * string() 输出宿主约定的 JSON。字段经 @SerializedName 固化键名，null 字段由 Gson 默认省略。
 */
class Result private constructor() {

    @SerializedName("class")
    private var classes: List<Any> = emptyList()

    @SerializedName("filters")
    private var filters: List<Any> = emptyList()

    @SerializedName("list")
    private var list: List<Any> = emptyList()

    @SerializedName("playUrl")
    private var playUrl: String? = null

    @SerializedName("url")
    private var url: String? = null

    @SerializedName("flag")
    private var flag: String? = null

    @SerializedName("jx")
    private var jx: String? = null

    @SerializedName("header")
    private var header: Map<String, String>? = null

    @SerializedName("parse")
    private var parse: Int = 0

    @SerializedName("page")
    private var page: Int = 1

    @SerializedName("pagecount")
    private var pagecount: Int = 1

    @SerializedName("total")
    private var total: Int = 0

    companion object {

        @JvmStatic
        fun get(): Result = Result()

        /** 任意对象转 JSON（jar 内常用工具入口） */
        @JvmStatic
        fun string(obj: Any?): String = if (obj == null) "" else runCatching { Gson().toJson(obj) }.getOrDefault("")
    }

    /** 视频列表 */
    fun list(items: List<*>?): Result {
        list = items?.filterNotNull() ?: emptyList()
        return this
    }

    /** 单个视频（详情等场景） */
    fun video(vod: Vod?): Result {
        list = if (vod != null) listOf(vod) else emptyList()
        return this
    }

    /** 分类列表 */
    fun classes(items: List<*>?): Result {
        classes = items?.filterNotNull() ?: emptyList()
        return this
    }

    /** 筛选条件列表 */
    fun filters(items: List<*>?): Result {
        filters = items?.filterNotNull() ?: emptyList()
        return this
    }

    fun url(value: String?): Result {
        url = value.orEmpty()
        return this
    }

    fun playUrl(value: String?): Result {
        playUrl = value.orEmpty()
        return this
    }

    fun flag(value: String?): Result {
        flag = value.orEmpty()
        return this
    }

    fun jx(value: String?): Result {
        jx = value.orEmpty()
        return this
    }

    fun header(value: Map<String, String>?): Result {
        header = value ?: emptyMap()
        return this
    }

    fun parse(value: Int): Result {
        parse = value
        return this
    }

    fun page(value: Int): Result {
        page = value
        return this
    }

    fun pagecount(value: Int): Result {
        pagecount = value
        return this
    }

    fun total(value: Int): Result {
        total = value
        return this
    }

    /** 输出 JSON */
    fun string(): String = string(this)
}

/** 分类条目（homeContent 的 class 数组元素） */
class Class private constructor() {

    @SerializedName("type_id")
    private var typeId: String? = null

    @SerializedName("type_name")
    private var typeName: String? = null

    @SerializedName("type_flag")
    private var typeFlag: String? = null

    companion object {

        @JvmStatic
        fun get(): Class = Class()
    }

    fun typeId(value: String?): Class {
        typeId = value
        return this
    }

    fun typeName(value: String?): Class {
        typeName = value
        return this
    }

    fun typeFlag(value: String?): Class {
        typeFlag = value
        return this
    }

    fun string(): String = Result.string(this)
}

/** 视频条目：字段与 TVBox vod 结构对齐 */
class Vod private constructor() {

    @SerializedName("vod_id")
    private var vodId: String? = null

    @SerializedName("vod_name")
    private var vodName: String? = null

    @SerializedName("vod_pic")
    private var vodPic: String? = null

    @SerializedName("vod_remarks")
    private var vodRemarks: String? = null

    @SerializedName("vod_content")
    private var vodContent: String? = null

    @SerializedName("vod_actor")
    private var vodActor: String? = null

    @SerializedName("vod_director")
    private var vodDirector: String? = null

    @SerializedName("vod_class")
    private var vodClass: String? = null

    @SerializedName("vod_year")
    private var vodYear: String? = null

    @SerializedName("vod_area")
    private var vodArea: String? = null

    @SerializedName("vod_lang")
    private var vodLang: String? = null

    @SerializedName("vod_state")
    private var vodState: String? = null

    @SerializedName("vod_tag")
    private var vodTag: String? = null

    @SerializedName("type_id")
    private var typeId: String? = null

    @SerializedName("type_name")
    private var typeName: String? = null

    @SerializedName("vod_play_from")
    private var vodFlags: String? = null

    @SerializedName("vod_play_url")
    private var vodPlayUrl: String? = null

    @SerializedName("subs")
    private var subs: MutableList<Sub> = ArrayList()

    companion object {

        @JvmStatic
        fun get(): Vod = Vod()
    }

    fun vodId(value: String?): Vod {
        vodId = value
        return this
    }

    fun vodName(value: String?): Vod {
        vodName = value
        return this
    }

    fun vodPic(value: String?): Vod {
        vodPic = value
        return this
    }

    fun vodRemarks(value: String?): Vod {
        vodRemarks = value
        return this
    }

    fun vodContent(value: String?): Vod {
        vodContent = value
        return this
    }

    fun vodActor(value: String?): Vod {
        vodActor = value
        return this
    }

    fun vodDirector(value: String?): Vod {
        vodDirector = value
        return this
    }

    fun vodClass(value: String?): Vod {
        vodClass = value
        return this
    }

    fun vodYear(value: String?): Vod {
        vodYear = value
        return this
    }

    fun vodArea(value: String?): Vod {
        vodArea = value
        return this
    }

    fun vodLang(value: String?): Vod {
        vodLang = value
        return this
    }

    fun vodState(value: String?): Vod {
        vodState = value
        return this
    }

    fun vodTag(value: String?): Vod {
        vodTag = value
        return this
    }

    fun typeId(value: String?): Vod {
        typeId = value
        return this
    }

    fun typeName(value: String?): Vod {
        typeName = value
        return this
    }

    /** 追加一条播放线路（Play 组装完成后调用） */
    fun play(play: Play?): Vod {
        if (play == null) return this
        val flagLine = play.flag()
        val urlLine = play.url()
        if (flagLine.isBlank() || urlLine.isBlank()) return this
        vodFlags = joinLine(vodFlags, flagLine)
        vodPlayUrl = joinLine(vodPlayUrl, urlLine)
        return this
    }

    /** 直接设置播放线路原始串（部分 jar 用此方式回填） */
    fun setPlayData(flags: String?, urls: String?): Vod {
        if (flags?.isNotBlank() == true) vodFlags = flags
        if (urls?.isNotBlank() == true) vodPlayUrl = urls
        return this
    }

    /** 追加字幕 */
    fun sub(name: String?, url: String?): Vod {
        subs += Sub.get().name(name).url(url)
        return this
    }

    fun string(): String = Result.string(this)

    private fun joinLine(existing: String?, added: String): String =
        listOfNotNull(existing?.takeIf { it.isNotBlank() }, added).joinToString(SEPARATOR_SOURCE)
}

/** 播放线路组装器：flag 为线路名，url 为 "第1集$xxx#第2集$yyy" */
class Play private constructor() {

    private var flags: MutableList<String> = ArrayList()
    private var urls: MutableList<String> = ArrayList()

    companion object {

        @JvmStatic
        fun get(): Play = Play()
    }

    /** 新增一条线路 */
    fun flag(value: String?): Play {
        flags += value.orEmpty()
        return this
    }

    /** 追加当前线路的整串播放地址（集与集之间用 # 分隔） */
    fun url(value: String?): Play {
        urls += value.orEmpty()
        return this
    }

    /** 追加单集（自动拼 "集名$集id"） */
    fun episode(name: String?, url: String?): Play {
        urls += name.orEmpty() + SEPARATOR_NAME_URL + url.orEmpty()
        return this
    }

    /** 批量追加单集 */
    fun episodes(items: List<Pair<String?, String?>>?): Play {
        items?.forEach { (name, url) -> episode(name, url) }
        return this
    }

    /** 线路串（$$$ 分隔） */
    fun flag(): String = flags.joinToString(SEPARATOR_SOURCE)

    /** 播放地址串（$$$ 分隔线路，# 分隔集） */
    fun url(): String = urls.joinToString(SEPARATOR_SOURCE)

    /** 组装为 Vod（常用便捷入口） */
    fun vod(): Vod = Vod.get().play(this)

    fun string(): String = Result.string(this)
}

/** 字幕条目 */
class Sub private constructor() {

    @SerializedName("name")
    private var name: String? = null

    @SerializedName("url")
    private var url: String? = null

    @SerializedName("lang")
    private var lang: String? = null

    companion object {

        @JvmStatic
        fun get(): Sub = Sub()
    }

    fun name(value: String?): Sub {
        name = value
        return this
    }

    fun url(value: String?): Sub {
        url = value
        return this
    }

    fun lang(value: String?): Sub {
        lang = value
        return this
    }

    fun string(): String = Result.string(this)
}

/** 分类筛选条件（homeContent 的 filters 数组元素） */
class Filter private constructor() {

    @SerializedName("key")
    private var key: String? = null

    @SerializedName("name")
    private var name: String? = null

    @SerializedName("value")
    private var values: List<Value> = emptyList()

    /** 筛选值条目：n 为展示名，v 为取值 */
    class Value private constructor() {

        @SerializedName("n")
        private var name: String? = null

        @SerializedName("v")
        private var value: String? = null

        companion object {

            @JvmStatic
            fun get(): Value = Value()
        }

        fun n(input: String?): Value {
            name = input
            return this
        }

        fun v(input: String?): Value {
            value = input
            return this
        }

        fun string(): String = Result.string(this)
    }

    companion object {

        @JvmStatic
        fun get(): Filter = Filter()
    }

    fun key(value: String?): Filter {
        key = value
        return this
    }

    fun name(value: String?): Filter {
        name = value
        return this
    }

    fun value(items: List<Value>?): Filter {
        values = items ?: emptyList()
        return this
    }

    fun value(vararg items: Value?): Filter = value(items.filterNotNull())

    fun string(): String = Result.string(this)
}
