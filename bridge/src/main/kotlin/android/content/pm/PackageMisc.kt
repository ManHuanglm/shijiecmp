package android.content.pm

/** 桌面 stub：包信息基类（仅类加载校验） */
open class PackageItemInfo {

    var name: String? = null
}

/** 桌面 stub：Provider 信息（仅类加载校验） */
class ProviderInfo : PackageItemInfo()

/** 桌面 stub：应用签名（返回空字节数组，仅签名校验类爬虫引用） */
class Signature {

    private val bytes: ByteArray

    constructor(hex: String?) {
        bytes = hex?.toByteArray() ?: ByteArray(0)
    }

    constructor(bytes: ByteArray?) {
        this.bytes = bytes ?: ByteArray(0)
    }

    fun toByteArray(): ByteArray = bytes

    fun toCharsString(): String = String(bytes)
}
