package android.webkit

import android.content.Context

/** 桌面 stub：WebView 群（桌面端不加载网页，仅满足类加载校验） */
open class WebView {

    constructor()

    constructor(context: Context?)

    open fun loadUrl(url: String?) = Unit

    open fun loadData(data: String?, mimeType: String?, encoding: String?) = Unit

    open fun getSettings(): WebSettings = WebSettings()

    open fun setWebViewClient(client: WebViewClient?) = Unit

    open fun destroy() = Unit
}

open class WebSettings {

    var userAgentString: String? = null
    var javaScriptEnabled: Boolean = false
    var domStorageEnabled: Boolean = false
    var blockNetworkImage: Boolean = false
    var useWideViewPort: Boolean = false
    var loadWithOverviewMode: Boolean = false

    fun setSupportZoom(support: Boolean) = Unit

    fun setBuiltInZoomControls(enabled: Boolean) = Unit
}

open class WebViewClient {

    fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
}

open class WebResourceRequest

open class WebResourceResponse {

    constructor()

    constructor(mimeType: String?, encoding: String?, data: java.io.InputStream?)
}
