package android.content.pm

/**
 * 桌面 stub：android.content.pm.PackageManager 最小面（getPackageInfo 返回占位
 * PackageInfo；NameNotFoundException 为签名兼容 checked 异常）。
 */
open class PackageManager {

    open fun getPackageInfo(packageName: String?, flags: Int): PackageInfo =
        PackageInfo().also { it.packageName = packageName ?: "" }

    /** 桌面 stub：应用信息（B 站等爬虫读 metaData 时提供非空占位） */
    open fun getApplicationInfo(packageName: String?, flags: Int): ApplicationInfo =
        ApplicationInfo().also { it.packageName = packageName ?: "" }

    /** 桌面 stub：应用标签（TVBoxOSC 等爬虫 Init 引导读应用名时提供占位） */
    open fun getApplicationLabel(info: ApplicationInfo?): CharSequence = "影视"

    class NameNotFoundException : Exception {

        constructor() : super()

        constructor(name: String?) : super(name)
    }
}
