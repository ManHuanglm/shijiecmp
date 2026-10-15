package com.tencent.smtt.sdk

/*
 * 桌面 stub：腾讯 X5 WebView SDK 引用面（pg Config 等爬虫引用 TbsListener/TbsDownloaderListener，
 * 桌面端无 X5 内核，仅满足类加载校验）
 */

interface TbsListener {

    fun onDownloadFinish(errorCode: Int)

    fun onInstallFinish(errorCode: Int)

    fun onDownloadProgress(progress: Int)
}

interface TbsDownloaderListener {

    fun onDownloadFinish(errorCode: Int)

    fun onInstallFinish(errorCode: Int)

    fun onDownloadProgress(progress: Int)
}

object QbSdk {

    fun initX5Environment(preInitCallback: (() -> Unit)?, cb: PreInitCallback?) = Unit

    fun setTbsListener(listener: TbsListener?) = Unit

    fun isTbsInitialized(): Boolean = false

    fun canLoadX5(context: android.content.Context?): Boolean = false

    interface PreInitCallback {

        fun onViewInitFinished(isX5: Boolean)
    }
}

open class CookieManager {

    companion object {

        @JvmStatic
        fun getInstance(): CookieManager = CookieManager()
    }

    fun setAcceptCookie(accept: Boolean) = Unit

    fun acceptCookie(): Boolean = false

    fun removeAllCookies(callback: ValueCallback<Boolean>?) = Unit

    fun flush() = Unit
}

interface ValueCallback<T>

open class WebChromeClient : android.webkit.WebChromeClient()

open class WebSettings {

    var userAgentString: String? = null

    var javaScriptEnabled: Boolean = false

    var domStorageEnabled: Boolean = false

    fun setSupportZoom(support: Boolean) = Unit
}

open class WebView : android.view.View {

    constructor()

    constructor(context: android.content.Context?)

    fun setWebViewClient(client: WebViewClient?) = Unit

    fun setWebChromeClient(client: WebChromeClient?) = Unit

    fun getSettings(): WebSettings = WebSettings()

    fun loadUrl(url: String?) = Unit

    fun loadDataWithBaseURL(baseUrl: String?, data: String?, mimeType: String?, encoding: String?, historyUrl: String?) = Unit

    fun setDownloadListener(listener: android.webkit.ValueCallback?) = Unit
}

open class WebViewClient {

    fun shouldOverrideUrlLoading(view: WebView?, request: com.tencent.smtt.export.external.interfaces.WebResourceRequest?): Boolean = false
}