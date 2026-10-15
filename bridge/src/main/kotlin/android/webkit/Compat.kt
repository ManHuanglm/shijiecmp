/* 桌面 stub 补充（auto generated）：保证爬虫 jar 类解析 */
package android.webkit

open class ConsoleMessage {

    fun messageLevel(): String? = null

    fun message(): String? = null

    fun lineNumber(): Int = 0

    fun sourceId(): String? = null
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class JavascriptInterface

open class MimeTypeMap {

    companion object {

        @JvmStatic
        fun getSingleton(): MimeTypeMap = MimeTypeMap()
    }

    fun getExtensionFromMimeType(mimeType: String?): String? = null

    fun getMimeTypeFromExtension(extension: String?): String? = null
}

open class SslErrorHandler {

    fun proceed() = Unit

    fun cancel() = Unit
}

open class WebStorage