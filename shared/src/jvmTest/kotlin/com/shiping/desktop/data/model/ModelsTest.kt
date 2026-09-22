package com.shiping.desktop.data.model

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 业务模型：gson 反序列化（@SerializedName 蛇形字段）与播放源拆分逻辑 */
class ModelsTest {

    private val gson = Gson()

    @Test
    fun vod蛇形字段反序列化() {
        val json = """
            {"vod_id": 42, "vod_name": "测试影片", "vod_remarks": "已完结",
             "vod_pic": "https://img.example.com/a.jpg", "vod_play_from": "线路一",
             "vod_play_url": "第01集${'$'}https://v.example.com/1.mp4"}
        """.trimIndent()
        val vod = gson.fromJson(json, Vod::class.java)
        assertEquals(42, vod.vodId)
        assertEquals("测试影片", vod.vodName)
        assertEquals("已完结", vod.vodRemarks)
        assertEquals("线路一", vod.vodPlayFrom)
    }

    @Test
    fun 多线路多集拆分_保留URL中的美元符() {
        val vod = Vod(
            vodPlayFrom = "线路A${'$'}${'$'}${'$'}线路B",
            vodPlayUrl = "第1集${'$'}http://a/1.mp4#第2集${'$'}http://a/2.mp4?x=1${'$'}&t=2${'$'}${'$'}${'$'}正片${'$'}http://b/full.mp4",
        )
        val sources = vod.parsePlaySources()
        assertEquals(2, sources.size)
        assertEquals("线路A", sources[0].name)
        assertEquals(2, sources[0].episodes.size)
        assertEquals("http://a/1.mp4", sources[0].episodes[0].url)
        // 第二个 $ 之后的完整保留（query 参数含 $ 不截断）
        assertEquals("http://a/2.mp4?x=1$&t=2", sources[0].episodes[1].url)
        assertEquals("正片", sources[1].episodes.single().name)
        assertEquals("http://b/full.mp4", sources[1].episodes.single().url)
    }

    @Test
    fun 无分隔符的集行被过滤() {
        val vod = Vod(vodPlayFrom = "线路", vodPlayUrl = "坏行#第1集${'$'}http://a/1.mp4")
        val episodes = vod.parsePlaySources().single().episodes
        assertEquals(1, episodes.size)
        assertEquals("第1集", episodes[0].name)
    }

    @Test
    fun 详情补全_只填列表缺失字段() {
        val listVod = Vod(vodId = 1, vodName = "片名", vodRemarks = "", vodPic = "")
        val detail = Vod(vodId = 1, vodName = "片名全集", vodRemarks = "第08集", vodPic = "https://img/x.jpg", vodScore = "8.5")
        val merged = listVod.mergeWithDetail(detail)
        // 列表项名称优先（部分源详情名与列表名不一致）
        assertEquals("片名", merged.vodName)
        // 空字段由详情补全，已有字段不被覆盖
        assertEquals("第08集", merged.vodRemarks)
        assertEquals("https://img/x.jpg", merged.vodPic)
        assertEquals("8.5", merged.vodScore)
    }

    @Test
    fun 封面兜底链_大图缩略图侧图() {
        assertEquals("slide", Vod(vodPicSlide = "slide").safePic)
        assertEquals("thumb", Vod(vodPicThumb = "thumb", vodPicSlide = "slide").safePic)
        assertEquals("main", Vod(vodPic = "main", vodPicThumb = "thumb").safePic)
        assertTrue(Vod().safePic.isEmpty())
    }
}
