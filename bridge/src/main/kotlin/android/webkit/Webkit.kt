package android.webkit

import android.content.Context

/** 桌面 stub：WebView 群（桌面端不加载网页，仅满足类加载校验；须挂 View 层级供验证通过） */
open class WebView : android.view.View {

    constructor()

    constructor(context: Context?)

    fun setWebChromeClient(client: WebChromeClient?) = Unit

    fun addJavascriptInterface(obj: Any?, name: String?) = Unit

    fun setLayerType(layerType: Int, paint: android.graphics.Paint?) = Unit

    fun evaluateJavascript(script: String?, callback: ValueCallback?) = Unit

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
