package android.content.res

import android.util.DisplayMetrics

/** 桌面 stub：颜色状态表（仅类加载校验） */
class ColorStateList {

    companion object {

        @JvmStatic
        fun valueOf(color: Int): ColorStateList = ColorStateList()
    }
}

/** 桌面 stub：配置信息（桌面端按 API 30 默认值） */
class Configuration {

    var orientation: Int = Configuration_Unknown
    var densityDpi: Int = 160
    var screenWidthDp: Int = 1920
    var screenHeightDp: Int = 1080
    var fontScale: Float = 1.0f

    companion object {

        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        private const val Configuration_Unknown = 0
    }
}

/** 桌面 stub：资源访问（无资源表，取值全部走默认值） */
class Resources {

    fun getColor(id: Int): Int = 0

    fun getString(id: Int): String = ""

    fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = 0

    fun getDisplayMetrics(): DisplayMetrics = DisplayMetrics()
}

/** 桌面 stub：XML 资源解析器（仅类加载校验，桌面端无资源表） */
interface XmlResourceParser : AutoCloseable {

    fun next(): Int

    fun getEventType(): Int
}
