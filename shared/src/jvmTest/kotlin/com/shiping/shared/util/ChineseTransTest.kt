package com.shiping.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 繁体转简体字符映射验证（移植自 FongMi catvod Trans 表） */
class ChineseTransTest {

    @Test
    fun 繁体转简体逐字替换() {
        assertEquals("繁体转简体搜索", ChineseTrans.t2s("繁體轉簡體搜索"))
        assertEquals("剧场版", ChineseTrans.t2s("劇場版"))
    }

    @Test
    fun 简体输入零改动() {
        val text = "仙逆剧场版弑仙之战2026"
        assertEquals(text, ChineseTrans.t2s(text))
    }

    @Test
    fun 空值与英文原样返回() {
        assertNull(ChineseTrans.t2s(null))
        assertEquals("", ChineseTrans.t2s(""))
        assertEquals("One Piece", ChineseTrans.t2s("One Piece"))
    }
}
