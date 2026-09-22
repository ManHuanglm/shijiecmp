package android.text

/**
 * 桌面 stub：android.text.TextUtils 最小实现（jar 内爬虫常用的空判/比较入口）。
 */
object TextUtils {

    @JvmStatic
    fun isEmpty(str: CharSequence?): Boolean = str == null || str.length == 0

    @JvmStatic
    fun equals(a: CharSequence?, b: CharSequence?): Boolean {
        if (a === b) return true
        val len = a?.length ?: return b == null
        if (b == null || b.length != len) return false
        if (a is String && b is String) return a == b
        for (index in 0 until len) if (a[index] != b[index]) return false
        return true
    }

    @JvmStatic
    fun isDigitsOnly(str: CharSequence?): Boolean {
        if (str == null) return false
        for (index in 0 until str.length) {
            val c = str[index]
            if (c !in '0'..'9') return false
        }
        return true
    }

    @JvmStatic
    fun getTrimmedLength(s: CharSequence): Int {
        var end = s.length - 1
        var start = 0
        while (start <= end && s[start] <= ' ') start++
        while (end >= start && s[end] <= ' ') end--
        return end - start + 1
    }

    @JvmStatic
    fun join(delimiter: CharSequence, tokens: Iterable<*>): String =
        tokens.joinToString(delimiter.toString())

    @JvmStatic
    fun join(delimiter: CharSequence, tokens: Array<Any?>): String =
        tokens.joinToString(delimiter.toString())

    /** 桌面 stub：截断样式枚举（仅签名引用） */
    enum class TruncateAt {
        START, MIDDLE, END, MARQUEE
    }
}
