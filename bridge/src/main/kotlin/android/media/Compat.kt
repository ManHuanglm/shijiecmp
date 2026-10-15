/* 桌面 stub 补充（auto generated）：保证爬虫 jar 类解析 */
package android.media

open class MediaCodec {

    class BufferInfo {

        var offset: Int = 0

        var size: Int = 0

        var presentationTimeUs: Long = 0

        var flags: Int = 0
    }
}

open class MediaCrypto

open class MediaFormat {

    companion object {

        const val KEY_MIME = "mime"

        const val KEY_WIDTH = "width"

        const val KEY_HEIGHT = "height"
    }

    fun getString(name: String): String? = null

    fun getInteger(name: String): Int = 0

    fun containsKey(name: String): Boolean = false
}