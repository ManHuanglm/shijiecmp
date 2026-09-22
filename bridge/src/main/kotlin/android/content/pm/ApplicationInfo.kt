package android.content.pm

import android.os.Bundle

/**
 * 桌面 stub：android.content.pm.ApplicationInfo（爬虫读取 sourceDir/packageName
 * 等字段时提供非空占位）。Android 侧均为 public 字段，爬虫（dex 转译后）直接
 * getfield 访问，因此必须用 @JvmField 暴露真实 public 字段而非 getter/setter。
 */
class ApplicationInfo {

    @JvmField
    var packageName: String = "com.shiping.probe"

    @JvmField
    var processName: String = "com.shiping.probe"

    @JvmField
    var sourceDir: String = System.getProperty("java.io.tmpdir")

    @JvmField
    var publicSourceDir: String = sourceDir

    @JvmField
    var dataDir: String = System.getProperty("java.io.tmpdir")

    @JvmField
    var nativeLibraryDir: String = System.getProperty("java.io.tmpdir")

    @JvmField
    var flags: Int = 0

    @JvmField
    var targetSdkVersion: Int = 30

    @JvmField
    var metaData: Bundle = Bundle()
}
