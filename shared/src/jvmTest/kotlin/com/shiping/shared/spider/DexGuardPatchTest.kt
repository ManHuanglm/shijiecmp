package com.shiping.shared.spider

import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** DexGuardPatch 自杀守卫中和补丁自校验（依赖本机已解出的 TVBoxOSC dex，缺失即跳过） */
class DexGuardPatchTest {

    private val dex = File(System.getProperty("java.io.tmpdir"), "vlc_spider_check.dex")

    @Test
    fun 补丁改写字符串并重密封头校验() {
        if (!dex.isFile) return
        val origin = dex.readBytes()
        val patched = DexGuardPatch.patch(origin.copyOf())

        // jar 层补丁：TVBoxOSC 本地包 jar（zip 容器）整体打补丁并解包复核
        val jar = sequenceOf(File(".."), File("."))
            .map { java.io.File(it, "TVBoxOSC/本地仓/spider.jar") }
            .firstOrNull { it.isFile }
        if (jar != null) {
            val patchedJar = DexGuardPatch.patchJar(jar.readBytes())
            val dexInJar = java.util.zip.ZipInputStream(patchedJar.inputStream()).use { z ->
                generateSequence { z.nextEntry }
                    .firstOrNull { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                    ?.let { z.readBytes() }
            } ?: ByteArray(0)
            assertTrue(
                !String(dexInJar, Charsets.ISO_8859_1).contains("killProcess"),
                "jar 内 dex 不应再含 killProcess",
            )
            assertTrue(String(dexInJar, Charsets.ISO_8859_1).contains("killProcesX"), "jar 内 dex 应含改名串")
        }

        // 1) 字符串已改写且等长（文件大小不变）
        assertEquals(origin.size, patched.size)
        val originText = String(origin, Charsets.ISO_8859_1)
        val patchedText = String(patched, Charsets.ISO_8859_1)
        assertTrue(!patchedText.contains("killProcess"), "补丁后不应再含 killProcess 原名")
        assertTrue(patchedText.contains("killProcesX"), "补丁后应含改名串")
        assertTrue(originText.contains("killProcess"), "原 dex 应含 killProcess（前置校验）")

        // 2) 头部自洽：按补丁器规则重算 checksum/signature 应与文件头存储值一致
        assertEquals(
            runCatching { MessageDigest.getInstance("SHA-1").digest(patched.copyOfRange(32, patched.size)) }
                .getOrDefault(ByteArray(0)).toList(),
            patched.copyOfRange(12, 32).toList(),
            "signature 头应与重算一致",
        )
        val adler = Adler32().apply { update(patched, 12, patched.size - 12) }.value
        val stored = (patched[8].toInt() and 0xFF) or
            ((patched[9].toInt() and 0xFF) shl 8) or
            ((patched[10].toInt() and 0xFF) shl 16) or
            ((patched[11].toInt() and 0xFF) shl 24)
        assertEquals(adler.toInt(), stored, "checksum 头应与重算一致")

        // 3) 幂等：再次补丁应无变化（无 killProcess 命中即原样返回）
        assertTrue(DexGuardPatch.patch(patched.copyOf()).contentEquals(patched), "补丁应幂等")
    }
}
