package com.shiping.shared.player

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS AES-128 加密分片解密（METHOD=AES-128，CBC/PKCS7）。
 * 密钥为清单 #EXT-X-KEY 指向的 16 字节文件；IV 优先取显式 IV 属性，
 * 未声明时按 RFC 8216 使用分片的媒体序列号（128 位大端，高 64 位补零）。
 */
object HlsAesDecryptor {

    /** 解密单分片数据；解密失败（密钥错误/数据损坏）抛 crypto 异常由调用方重试 */
    fun decrypt(data: ByteArray, key: ByteArray, ivHex: String?, mediaSequenceNumber: Long): ByteArray {
        val iv = when {
            !ivHex.isNullOrBlank() -> hexToBytes(ivHex)
            else -> sequenceIv(mediaSequenceNumber)
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** 媒体序列号 IV：128 位大端整数，高 64 位补零 */
    private fun sequenceIv(mediaSequenceNumber: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) {
            iv[8 + i] = (mediaSequenceNumber shr ((7 - i) * 8)).toByte()
        }
        return iv
    }

    /** 十六进制串转字节数组（兼容 0x 前缀，奇数长度视为异常抛出） */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty() || clean.length % 2 != 0) {
            throw IllegalStateException("IV 十六进制格式异常: $hex")
        }
        return ByteArray(clean.length / 2) { i ->
            val high = Character.digit(clean[i * 2], 16)
            val low = Character.digit(clean[i * 2 + 1], 16)
            if (high < 0 || low < 0) throw IllegalStateException("IV 含非十六进制字符: $hex")
            ((high shl 4) + low).toByte()
        }
    }
}
