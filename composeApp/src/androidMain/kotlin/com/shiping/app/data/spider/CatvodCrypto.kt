package com.github.catvod.utils

import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * 爬虫加解密桥接（com.github.catvod.utils.Crypto，仿 FongMi/TV catvod 模块）。
 *
 * jar 内签名/内容加解密均经由本类；纯 JDK 实现（无 android.* 依赖），
 * 因此宿主配置解析器（TvBoxConfigParser 的 $# 解密）也直接复用本类。
 * aes/des/rsa 通用入口失败时返回空串，md5/decryptAesCbc 异常向外抛（与 FongMi 一致）。
 */
class Crypto private constructor() {

    companion object {

        private const val AES_BLOCK_SIZE = 16
        private const val DES_BLOCK_SIZE = 8
        private const val DES_EDE_TWO_KEY_SIZE = 16
        private const val DES_EDE_THREE_KEY_SIZE = 24
        private const val BUFFER_SIZE = 64 * 1024
        private val HEX = "0123456789abcdef".toCharArray()
        private val OAEP_SHA1 = OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

        @JvmStatic
        fun md5(value: String?): String {
            if (value.isNullOrEmpty()) return ""
            return toHex(newDigest("MD5").digest(value.toByteArray(Charsets.UTF_8)))
        }

        @JvmStatic
        fun md5(file: File?): String = runCatching {
            val target = file ?: return ""
            toHex(digest(target, newDigest("MD5")))
        }.getOrDefault("")

        @JvmStatic
        fun equals(file: File?, expected: String?): Boolean {
            if (expected.isNullOrEmpty()) return false
            return expected.equals(md5(file), ignoreCase = true)
        }

        @JvmStatic
        fun sha256(file: File?): ByteArray = digest(file ?: File(""), newDigest("SHA-256"))

        @JvmStatic
        fun newDigest(algorithm: String?): MessageDigest =
            MessageDigest.getInstance(algorithm ?: "MD5")

        /** AES/CBC/PKCS5Padding 解密（$# 配置与 jar 内通用解密入口） */
        @JvmStatic
        fun decryptAesCbc(data: ByteArray?, key: ByteArray?, iv: ByteArray?): ByteArray {
            checkNotNull(data) { "data is null" }
            checkNotNull(key) { "key is null" }
            checkNotNull(iv) { "IV is required for CBC mode" }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(data)
        }

        /** AES 通用入口：mode 形如 AES/CBC、AES/ECB，输入输出按需 Base64 */
        @JvmStatic
        fun aes(
            mode: String?,
            encrypt: Boolean,
            input: String?,
            inputBase64: Boolean,
            key: String?,
            iv: String?,
            outputBase64: Boolean,
        ): String = runCatching {
            val transformation = when {
                mode.isNullOrEmpty() -> "AES/CBC/PKCS5Padding"
                mode.startsWith("AES/CBC") -> "AES/CBC/PKCS5Padding"
                mode.startsWith("AES/ECB") -> "AES/ECB/PKCS5Padding"
                else -> mode + "Padding"
            }
            val keyBytes = padParameter(key.orEmpty().toByteArray(Charsets.UTF_8), AES_BLOCK_SIZE)
            val cipher = Cipher.getInstance(transformation)
            val opMode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
            val ivBytes = ivBytes(iv, AES_BLOCK_SIZE)
            if (ivBytes != null) cipher.init(opMode, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            else cipher.init(opMode, SecretKeySpec(keyBytes, "AES"))
            encodeOutput(cipher.doFinal(decodeInput(input, inputBase64)), outputBase64)
        }.getOrDefault("")

        /** DESede 通用入口：mode 形如 DESede/CBC，key 16 位自动扩展 24 位 */
        @JvmStatic
        fun des(
            mode: String?,
            encrypt: Boolean,
            input: String?,
            inputBase64: Boolean,
            key: String?,
            iv: String?,
            outputBase64: Boolean,
        ): String = runCatching {
            val transformation = if (mode.orEmpty().startsWith("DESede/CBC")) {
                "DESede/CBC/PKCS5Padding"
            } else {
                mode.orEmpty() + "Padding"
            }
            val cipher = Cipher.getInstance(transformation)
            val opMode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
            val keySpec = SecretKeySpec(getDesEdeKey(key), "DESede")
            val ivBytes = ivBytes(iv, DES_BLOCK_SIZE)
            if (ivBytes != null) cipher.init(opMode, keySpec, IvParameterSpec(ivBytes))
            else cipher.init(opMode, keySpec)
            encodeOutput(cipher.doFinal(decodeInput(input, inputBase64)), outputBase64)
        }.getOrDefault("")

        /** RSA 通用入口：mode 支持 RSA/PKCS1、RSA/None/NoPadding、RSA/None/OAEPPadding */
        @JvmStatic
        fun rsa(
            mode: String?,
            publicKey: Boolean,
            encrypt: Boolean,
            input: String?,
            inputBase64: Boolean,
            key: String?,
            outputBase64: Boolean,
        ): String = runCatching {
            val transformation = when (mode) {
                "RSA/PKCS1" -> "RSA/ECB/PKCS1Padding"
                "RSA/None/NoPadding" -> "RSA/ECB/NoPadding"
                "RSA/None/OAEPPadding" -> "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
                else -> return@runCatching ""
            }
            val cipher = Cipher.getInstance(transformation)
            val rsaKey = generateRsaKey(publicKey, key.orEmpty())
            val opMode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
            if (transformation.endsWith("OAEPWithSHA-1AndMGF1Padding")) cipher.init(opMode, rsaKey, OAEP_SHA1)
            else cipher.init(opMode, rsaKey)
            encodeOutput(cipher.doFinal(decodeInput(input, inputBase64)), outputBase64)
        }.getOrDefault("")

        private fun digest(file: File, digest: MessageDigest): ByteArray {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count)
            }
            return digest.digest()
        }

        private fun ivBytes(iv: String?, blockSize: Int): ByteArray? =
            if (iv.isNullOrEmpty()) null else padParameter(iv.toByteArray(Charsets.UTF_8), blockSize)

        private fun getDesEdeKey(key: String?): ByteArray {
            val bytes = padParameter(key.orEmpty().toByteArray(Charsets.UTF_8), DES_EDE_TWO_KEY_SIZE)
            if (bytes.size != DES_EDE_TWO_KEY_SIZE) return bytes
            val expanded = Arrays.copyOf(bytes, DES_EDE_THREE_KEY_SIZE)
            System.arraycopy(bytes, 0, expanded, DES_EDE_TWO_KEY_SIZE, DES_BLOCK_SIZE)
            return expanded
        }

        private fun padParameter(value: ByteArray, minLength: Int): ByteArray =
            if (value.size < minLength) Arrays.copyOf(value, minLength) else value

        private fun generateRsaKey(publicKey: Boolean, value: String): java.security.Key {
            val begin = if (publicKey) "-----BEGIN PUBLIC KEY-----" else "-----BEGIN PRIVATE KEY-----"
            val end = if (publicKey) "-----END PUBLIC KEY-----" else "-----END PRIVATE KEY-----"
            val body = value.replace("\r", "").replace("\n", "").replace(begin, "").replace(end, "")
            val factory = KeyFactory.getInstance("RSA")
            val bytes = Base64.getMimeDecoder().decode(body)
            return if (publicKey) {
                factory.generatePublic(X509EncodedKeySpec(bytes))
            } else {
                factory.generatePrivate(PKCS8EncodedKeySpec(bytes))
            }
        }

        private fun decodeInput(input: String?, base64: Boolean): ByteArray =
            if (base64) {
                Base64.getMimeDecoder().decode(input.orEmpty().replace('_', '/').replace('-', '+'))
            } else {
                input.orEmpty().toByteArray(Charsets.UTF_8)
            }

        private fun encodeOutput(output: ByteArray, base64: Boolean): String =
            if (base64) Base64.getEncoder().encodeToString(output) else String(output, Charsets.UTF_8)

        private fun toHex(bytes: ByteArray): String {
            val result = CharArray(bytes.size * 2)
            for (index in bytes.indices) {
                val value = bytes[index].toInt() and 0xff
                result[index * 2] = HEX[value ushr 4]
                result[index * 2 + 1] = HEX[value and 0x0f]
            }
            return String(result)
        }
    }
}
