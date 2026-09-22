package android.content.pm

/**
 * 桌面 stub：android.content.pm.PackageInfo（public 字段直接访问，需 @JvmField）。
 */
class PackageInfo {

    @JvmField
    var packageName: String = ""

    @JvmField
    var versionName: String = "1.0.0"

    @JvmField
    var versionCode: Int = 1

    @JvmField
    var applicationInfo: ApplicationInfo = ApplicationInfo()

    /** 签名数组（B 站等爬虫读取应用签名参与请求签名；桌面端提供占位签名） */
    @JvmField
    var signatures: Array<Signature> = arrayOf(Signature("shiping-desktop"))

    @JvmField
    var firstInstallTime: Long = 0L

    @JvmField
    var lastUpdateTime: Long = 0L
}
