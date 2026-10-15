package com.shiping.shared.spider

import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * dex 自杀守卫中和补丁（本地包防盗版校验通用方案）：
 * TVBox 生态部分包（如 TVBoxOSC）的 Init.init 会校验宿主包名/应用名白名单，
 * 不匹配时起延迟线程调用 Process.killProcess 自杀。本补丁在 jar 落入应用缓存时，
 * 把 dex 字符串池中的 "killProcess" 等长改写为 "killProcesX"（仅改末字节），
 * 守卫线程届时调用即抛 NoSuchMethodError 自然死亡，进程与应用均不受影响；
 * jar 签名校验只发生在安装器，DexClassLoader 不校验 APK 签名，仅需重算 dex 头
 * 的 checksum(Adler32) 与 signature(SHA1)。
 * 兼顾其他自杀形态：System.exit / Runtime.exit 同步改名前缀命中不到的极少，
 * 遇到再扩表。无命中返回原字节。
 */
object DexGuardPatch {

    /**
     * jar 层补丁入口：jar 是 zip 容器，classes*.dex 在其中为压缩态，
     * 直接扫容器字节找不到明文方法名——必须解包 dex 打补丁后重打包。
     * 无 classes*.dex / 无命中时返回原字节。
     */
    fun patchJar(jarBytes: ByteArray): ByteArray = try {
        var patched = false
        val rebuilt = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(rebuilt).use { zip ->
            java.util.zip.ZipInputStream(jarBytes.inputStream()).use { source ->
                var entry = source.nextEntry
                while (entry != null) {
                    val data = source.readBytes()
                    val next = if (entry.name.matches(Regex("classes[0-9]*\\.dex"))) {
                        val before = data.copyOf()
                        val patchedDex = patch(data)
                        if (!patchedDex.contentEquals(before)) patched = true
                        patchedDex
                    } else {
                        data
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    zip.write(next)
                    zip.closeEntry()
                    entry = source.nextEntry
                }
            }
        }
        if (patched) rebuilt.toByteArray() else jarBytes
    } catch (t: Throwable) {
        jarBytes
    }

    /** 需要中和的自杀方法名（等长替换：末位字符改写，UTF-8 长度不变） */
    private const val KILL_PROCESS = "killProcess"
    private const val KILL_PROCESS_PATCHED = "killProcesX"

    /** dex 头字段偏移（小端） */
    private const val OFF_STRING_IDS_SIZE = 56
    private const val OFF_STRING_IDS_OFF = 60

    fun patch(bytes: ByteArray): ByteArray {
        return try {
            if (bytes.size < 112 || String(bytes, 0, 4, Charsets.US_ASCII) != "dex\n") return bytes
            val stringIdsSize = intAt(bytes, OFF_STRING_IDS_SIZE)
            val stringIdsOff = intAt(bytes, OFF_STRING_IDS_OFF)
            var hit = false
            for (i in 0 until stringIdsSize) {
                val dataOff = intAt(bytes, stringIdsOff + i * 4)
                if (matchesString(bytes, dataOff, KILL_PROCESS)) {
                    patchString(bytes, dataOff)
                    hit = true
                }
            }
            if (!hit) return bytes
            reseal(bytes)
            bytes
        } catch (t: Throwable) {
            bytes
        }
    }

    /** string_data_item：uleb128 utf16 长度 + MUTF-8 字节 + 0x00；比对目标串（ASCII 段） */
    private fun matchesString(bytes: ByteArray, dataOff: Int, target: String): Boolean {
        var p = dataOff
        var shift = 0
        var len = 0
        while (p < bytes.size) {
            val b = bytes[p].toInt() and 0xFF
            p++
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (len != target.length) return false
        for (i in target.indices) {
            if (p + i >= bytes.size || bytes[p + i] != target[i].code.toByte()) return false
        }
        return true
    }

    /** 等长改写末位字符（MUTF-8 长度前缀不变，偏移表零扰动） */
    private fun patchString(bytes: ByteArray, dataOff: Int) {
        var p = dataOff
        while (bytes[p].toInt() and 0x80 != 0) p++
        p++
        var end = p
        while (bytes[end] != 0.toByte()) end++
        bytes[end - 1] = 'X'.code.toByte()
    }

    private fun reseal(bytes: ByteArray) {
        // signature：SHA1(file[32..fileSize])，写回 file[12..31]
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size))
        System.arraycopy(sha, 0, bytes, 12, 20)
        // checksum：Adler32(file[12..fileSize])，写回 file[8..11]（小端）
        val adler = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value
        bytes[8] = (adler and 0xFF).toByte()
        bytes[9] = ((adler shr 8) and 0xFF).toByte()
        bytes[10] = ((adler shr 16) and 0xFF).toByte()
        bytes[11] = ((adler shr 24) and 0xFF).toByte()
    }

    private fun intAt(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)
}
