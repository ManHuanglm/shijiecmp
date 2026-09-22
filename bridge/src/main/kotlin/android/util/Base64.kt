package android.util

import java.util.Base64

/**
 * 桌面 stub：android.util.Base64 语义映射到 java.util.Base64。
 * NO_WRAP ↔ 无换行、DEFAULT ↔ 76 字符换行（行尾带 \n）、URL_SAFE ↔ base64url、
 * NO_PADDING ↔ 无 '='；解码宽松（自动去除空白并兼容 url-safe 字符）。
 */
object Base64 {

    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic
    fun encodeToString(input: ByteArray?, flags: Int): String {
        if (input == null) return ""
        val encoder = if (flags and URL_SAFE != 0) {
            if (flags and NO_PADDING != 0) Base64.getUrlEncoder().withoutPadding() else Base64.getUrlEncoder()
        } else {
            if (flags and NO_PADDING != 0) Base64.getEncoder().withoutPadding() else Base64.getEncoder()
        }
        val encoded = encoder.encodeToString(input)
        if (flags and NO_WRAP != 0) return encoded
        // Android DEFAULT：76 字符换行，行尾 \n（CRLF 时为 \r\n）
        val newline = if (flags and CRLF != 0) "\r\n" else "\n"
        return encoded.chunked(76).joinToString(newline, postfix = newline)
    }

    @JvmStatic
    fun encode(input: ByteArray?, flags: Int): ByteArray =
        encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun decodeToString(str: String?, flags: Int): String =
        String(decode(str, flags), Charsets.UTF_8)

    @JvmStatic
    fun decode(str: String?, flags: Int): ByteArray {
        if (str == null) return ByteArray(0)
        return decode(str.toByteArray(Charsets.US_ASCII), flags)
    }

    @JvmStatic
    fun decode(input: ByteArray?, flags: Int): ByteArray {
        if (input == null) return ByteArray(0)
        // 宽松解码：去除空白，url-safe 字符统一还原为标准字母表后用 MIME 解码器
        val compact = String(input, Charsets.US_ASCII)
            .filter { !it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        val normalized = compact.padEnd((compact.length + 3) / 4 * 4, '=')
        return Base64.getMimeDecoder().decode(normalized)
    }
}
