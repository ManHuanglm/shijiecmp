/* 桌面 stub 补充（auto generated）：腾讯 X5 导出接口引用面 */
package com.tencent.smtt.export.external.interfaces

open class SslError

interface SslErrorHandler {

    fun proceed()

    fun cancel()
}

interface WebResourceRequest

open class WebResourceResponse {

    constructor()

    constructor(mimeType: String?, encoding: String?, data: java.io.InputStream?)
}
