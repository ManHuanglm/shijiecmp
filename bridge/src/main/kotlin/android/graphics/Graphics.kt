package android.graphics

/** 桌面 stub：位图（仅满足类加载校验，桌面端不渲染） */
class Bitmap {

    enum class Config {
        ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE
    }
}

/** 桌面 stub：位图解码（返回空位图，调用方仅做展示用途） */
object BitmapFactory {

    @JvmStatic
    fun decodeFile(path: String?): Bitmap = Bitmap()

    @JvmStatic
    fun decodeByteArray(data: ByteArray?, offset: Int, length: Int): Bitmap = Bitmap()
}

/** 桌面 stub：颜色工具（保留常量与解析） */
object Color {

    const val BLACK = 0xFF000000.toInt()
    const val DKGRAY = 0xFF444444.toInt()
    const val GRAY = 0xFF888888.toInt()
    const val LTGRAY = 0xFFCCCCCC.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val CYAN = 0xFF00FFFF.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()
    const val TRANSPARENT = 0

    @JvmStatic
    fun parseColor(colorString: String): Int =
        runCatching { java.lang.Long.decode(colorString).toInt() }.getOrElse {
            throw IllegalArgumentException("Unknown color: $colorString", it)
        }

    @JvmStatic
    fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    @JvmStatic
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

/** 桌面 stub：矩形（仅字段存取） */
class Rect {

    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0

    constructor()

    constructor(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun width(): Int = right - left

    fun height(): Int = bottom - top
}

/** 桌面 stub：字体（仅类加载校验；fromAsset 返回空对象） */
class Typeface private constructor() {

    companion object {

        @JvmStatic
        fun create(family: String?, style: Int): Typeface = Typeface()

        @JvmStatic
        fun defaultFromStyle(style: Int): Typeface = Typeface()
    }
}
