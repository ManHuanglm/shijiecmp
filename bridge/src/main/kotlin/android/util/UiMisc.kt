package android.util

/** 桌面 stub：显示度量（仅字段存取） */
class DisplayMetrics {

    var widthPixels: Int = 1920
    var heightPixels: Int = 1080
    var density: Float = 1.0f
    var densityDpi: Int = 160
    var scaledDensity: Float = 1.0f
}

/** 桌面 stub：尺寸值容器（仅类加载校验） */
class TypedValue {

    var data: Int = 0
    var type: Int = 0

    companion object {

        const val COMPLEX_UNIT_DIP = 1
        const val COMPLEX_UNIT_SP = 2
        const val COMPLEX_UNIT_PX = 0

        @JvmStatic
        fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics?): Float = value
    }
}
