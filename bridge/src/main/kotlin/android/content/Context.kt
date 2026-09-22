package android.content

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import java.io.File

/**
 * 桌面 stub：android.content.Context 空壳（jar 内 Init.set(context)、Prefers、
 * Path.cache()/files() 等入口传入不崩即可）。
 */
open class Context {

    /** 数据根目录：宿主（DesktopContext）覆盖为用户主目录下的应用数据目录 */
    open val baseDir: File =
        File(System.getProperty("java.io.tmpdir"), "shiping-desktop-context").apply { mkdirs() }

    open fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }

    open fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }

    /** 数据库文件路径（sqlite 桩配套：保证 databases 目录存在） */
    open fun getDatabasePath(name: String?): File =
        File(File(baseDir, "databases").apply { mkdirs() }, name ?: "default.db")

    open fun getExternalFilesDir(type: String?): File? =
        File(baseDir, "external_$type").apply { mkdirs() }

    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        MemSharedPreferences.of(name ?: "default")

    open fun getAssets(): AssetManager = AssetManager()

    open fun getApplicationContext(): Context = this

    open fun getPackageName(): String = "com.shiping.desktop"

    open fun getApplicationInfo(): ApplicationInfo = ApplicationInfo()

    open fun getPackageManager(): PackageManager = PackageManager()

    /** 桌面 stub：内容解析器（Settings.Secure 等读取入口，返回空实现） */
    open fun getContentResolver(): ContentResolver = ContentResolver()

    /** 权限检查：桌面端无权限体系，一律授予 */
    open fun checkSelfPermission(permission: String?): Int = PERMISSION_GRANTED

    open fun getPackageName_(): String = getPackageName()

    open fun getSystemService(name: String?): Any? = null

    open fun getString(resId: Int): String = ""

    open fun getString(resId: Int, vararg formatArgs: Any?): String = ""

    companion object {

        const val MODE_PRIVATE = 0
        const val MODE_WORLD_READABLE = 1
        const val MODE_WORLD_WRITEABLE = 2
        const val MODE_APPEND = 32768
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val WIFI_SERVICE = "wifi"
        const val INPUT_METHOD_SERVICE = "input_method"
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val ACTIVITY_SERVICE = "activity"
        const val NOTIFICATION_SERVICE = "notification"
        const val CLIPBOARD_SERVICE = "clipboard"
        const val POWER_SERVICE = "power"
        const val VIBRATOR_SERVICE = "vibrator"
        const val AUDIO_SERVICE = "audio"
        const val MEDIA_ROUTER_SERVICE = "media_router"
    }
}
