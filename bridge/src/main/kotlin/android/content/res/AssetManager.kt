package android.content.res

import java.io.InputStream

/**
 * 桌面 stub：android.content.res.AssetManager（桌面无 assets，open 恒返回 null，
 * catvod Asset.open 捕获后按空资源处理）。
 */
class AssetManager {

    fun open(fileName: String): InputStream? = null

    fun open(fileName: String, accessMode: Int): InputStream? = null

    fun list(path: String?): Array<String> = arrayOf()
}
