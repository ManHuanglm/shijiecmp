package com.github.catvod.utils

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * org.json 语义守护测试：payload 爬虫依赖 Android 宽松 org.json 语义
 * （getString 对数字/布尔等非字符串类型做转换而非抛错）。
 * 本工程必须使用 com.vaadin.external.google:android-json，官方 JSON-java 会
 * 因严格抛错破坏爬虫解析——此测试即守护该依赖行为不被升级破坏。
 */
class JsonLenientSemanticsTest {

    @Test
    fun getString对数字值做宽松转换() {
        val obj = JSONObject("""{"n": 123, "f": 1.5, "b": true}""")
        assertEquals("123", obj.getString("n"))
        assertEquals("1.5", obj.getString("f"))
        assertEquals("true", obj.getString("b"))
    }

    @Test
    fun optString对缺失与null值宽松兜底() {
        val obj = JSONObject("""{"k": "v", "n": null}""")
        assertEquals("v", obj.optString("k"))
        assertEquals("", obj.optString("missing"))
        assertEquals("def", obj.optString("missing", "def"))
    }

    @Test
    fun catvodJson桥接_取值宽松不抛错() {
        val json = JSONObject("""{"code": 1, "msg": "ok", "list": []}""")
        // 桥接工具层（com.github.catvod.utils.Json）基于宽松语义构建，
        // 数字型 code 经 getString 取值不应抛 JSONException
        assertEquals("ok", json.getString("msg"))
        assertEquals(1, json.optInt("code"))
    }
}
