package android.os

import java.io.File

/**
 * 桌面 stub：android.os.Environment，外部存储根映射到系统临时目录
 * （jar 内 Path.root()/tv() 等入口的落盘位置）。
 */
object Environment {

    private const val PROBE_DIR_NAME = "desktop-probe"

    @JvmStatic
    @Deprecated("Android 已废弃，stub 仅为兼容 jar 调用")
    fun getExternalStorageDirectory(): File {
        val root = File(System.getProperty("java.io.tmpdir"), PROBE_DIR_NAME)
        if (!root.exists()) root.mkdirs()
        return root
    }

    @JvmStatic
    fun isExternalStorageEmulated(): Boolean = true

    @JvmStatic
    fun getExternalStorageState(): String = "mounted"
}
