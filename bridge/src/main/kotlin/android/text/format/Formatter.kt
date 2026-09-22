package android.text.format

import java.util.Locale

/** 桌面 stub：文件大小格式化（个别爬虫展示用，按十进制单位输出） */
object Formatter {

    @JvmStatic
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = size.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.size - 1) {
            value /= 1024.0
            index++
        }
        return String.format(Locale.US, if (index == 0) "%.0f%s" else "%.1f%s", value, units[index])
    }

    @JvmStatic
    fun formatShortFileSize(size: Long): String = formatFileSize(size)
}
