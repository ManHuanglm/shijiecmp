package com.shiping.desktop.data.prefs

import com.shiping.shared.prefs.KeyValueStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DesktopPrefs 行为与 KeyValueStore 契约（XML Properties 持久化） */
class DesktopPrefsTest {

    private val tempDir: String = Files.createTempDirectory("shijiecmp-prefs-test").toString()

    @AfterTest
    fun cleanUp() {
        java.io.File(tempDir).deleteRecursively()
    }

    private fun newPrefs(): DesktopPrefs = DesktopPrefs(java.io.File(tempDir))

    @Test
    fun 默认值_键不存在时返回约定缺省() {
        val prefs = newPrefs()
        assertEquals("", prefs.getString("missing"))
        assertEquals("def", prefs.getString("missing", "def"))
        assertEquals(0, prefs.getInt("missing"))
        assertEquals(7, prefs.getInt("missing", 7))
        assertEquals(0L, prefs.getLong("missing"))
        assertEquals(0f, prefs.getFloat("missing"))
        assertFalse(prefs.getBoolean("missing"))
        assertTrue(prefs.getBoolean("missing", true))
    }

    @Test
    fun 写入读取_跨实例持久化() {
        newPrefs().apply {
            put("str", "值")
            put("int", 42)
            put("long", 1234567890123L)
            put("float", 1.5f)
            put("bool", true)
        }
        val reloaded = newPrefs()
        assertEquals("值", reloaded.getString("str"))
        assertEquals(42, reloaded.getInt("int"))
        assertEquals(1234567890123L, reloaded.getLong("long"))
        assertEquals(1.5f, reloaded.getFloat("float"))
        assertTrue(reloaded.getBoolean("bool"))
    }

    @Test
    fun putNull移除键() {
        val prefs = newPrefs()
        prefs.put("k", "v")
        assertEquals("v", prefs.getString("k"))
        prefs.put("k", null)
        // 键移除后回落缺省值
        assertEquals("", prefs.getString("k"))
        assertEquals("def", prefs.getString("k", "def"))
    }

    @Test
    fun 类型容错_非法数值回落默认() {
        val prefs = newPrefs()
        prefs.put("notanumber", "abc")
        assertEquals(0, prefs.getInt("notanumber"))
        assertEquals(-1L, prefs.getLong("notanumber", -1L))
    }

    @Test
    fun 实现统一KeyValueStore接口() {
        val prefs: KeyValueStore = newPrefs()
        prefs.put("k", 1)
        assertEquals(1, prefs.getInt("k"))
    }
}
