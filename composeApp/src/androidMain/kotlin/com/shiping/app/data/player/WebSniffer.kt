package com.shiping.app.data.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shiping.app.util.AppLog
import com.shiping.app.util.Constants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView 网页嗅探解析器。
 *
 * 用于爬虫源 parse=1 场景：playerContent 返回的地址是网页解析页（如
 * http://x.com/neibu.php?url=yyy）而非媒体直链，ExoPlayer 无法播放。
 * 这里用不可见 WebView 加载解析页，监听页面发起的所有请求，
 * 命中第一个媒体直链（.m3u8/.mp4/.flv/.ts）即回调真实播放地址。
 * 参考 TV-fongmi CustomWebView 的嗅探思路。
 *
 * WebView 必须在主线程创建/销毁，实例由调用方持有，换线路/退出时调用 [cancel]。
 */
class WebSniffer {

    /** 主线程 Handler：WebView 生命周期操作统一调度 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否已结束（命中/超时/失败/取消），保证 onResult 只回调一次 */
    private val isFinished = AtomicBoolean(false)

    /** 嗅探使用的不可见 WebView（仅主线程访问） */
    private var webView: WebView? = null

    /** 首次加载的解析页地址本身不算命中：其 query 可能携带媒体字样（如 url=xx.m3u8） */
    private var initialUrl: String = ""

    /** 超时任务，结束时移除 */
    private var timeoutRunnable: Runnable? = null

    /**
     * 开始嗅探：加载 [url] 并监听媒体请求。
     * 命中回调真实媒体地址；超时或页面加载失败回调 null；保证只回调一次。
     */
    fun sniff(context: Context, url: String, timeoutMs: Long = SNIFF_TIMEOUT_MS, onResult: (String?) -> Unit) {
        cancel()
        isFinished.set(false)
        initialUrl = url
        mainHandler.post {
            if (isFinished.get()) return@post
            val appContext = context.applicationContext
            AppLog.i(TAG, "开始网页嗅探: $url")
            webView = createWebView(appContext, onResult)
            timeoutRunnable = Runnable {
                AppLog.w(TAG, "网页嗅探超时(${timeoutMs}ms): $url")
                finish(null, onResult)
            }.also { mainHandler.postDelayed(it, timeoutMs) }
            webView?.loadUrl(url)
        }
    }

    /** 取消进行中的嗅探并销毁 WebView（任意线程可调用，不触发 onResult） */
    fun cancel() {
        isFinished.set(true)
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        mainHandler.post { destroyWebView() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, onResult: (String?) -> Unit): WebView {
        val snifferWebView = WebView(context)
        snifferWebView.layoutParams = ViewGroup.LayoutParams(0, 0)
        snifferWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = MOBILE_UA
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        snifferWebView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val target = request.url.toString()
                if (isMediaUrl(target)) finish(target, onResult)
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val target = request.url.toString()
                if (isMediaUrl(target)) finish(target, onResult)
                return null
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // 主框架加载失败提前判负加快反馈；子资源失败忽略继续嗅探
                if (request.isForMainFrame) {
                    AppLog.w(TAG, "解析页加载失败: ${error.description}")
                    finish(null, onResult)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                // 解析站证书不规范较常见，放行继续嗅探
                handler.proceed()
            }
        }
        return snifferWebView
    }

    /** 嗅探结束：切回主线程销毁 WebView 并回调（shouldInterceptRequest 在子线程触发） */
    private fun finish(result: String?, onResult: (String?) -> Unit) {
        if (!isFinished.compareAndSet(false, true)) return
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        mainHandler.post {
            destroyWebView()
            onResult(result)
        }
    }

    /** 销毁嗅探 WebView，释放底层资源（主线程调用） */
    private fun destroyWebView() {
        val snifferWebView = webView ?: return
        webView = null
        runCatching {
            snifferWebView.stopLoading()
            (snifferWebView.parent as? ViewGroup)?.removeView(snifferWebView)
            snifferWebView.destroy()
        }.onFailure { AppLog.e(TAG, "嗅探 WebView 销毁失败", it) }
    }

    /** 判断请求地址是否为媒体直链：path 后缀精确匹配，或 query 中携带媒体标记（宽松判断） */
    private fun isMediaUrl(url: String): Boolean {
        // 与初始解析页相同不算命中；data:/blob: 或超长内联地址跳过避免误报
        if (url == initialUrl) return false
        if (url.length > MAX_CHECK_URL_LENGTH) return false
        if (url.startsWith("data:") || url.startsWith("blob:")) return false
        val lowerUrl = url.substringBefore("#").lowercase()
        // 已知解析失败的占位媒体（yparse 404 提示片等）不算命中：继续嗅探等待真实直链
        if (PLACEHOLDER_MARKERS.any { lowerUrl.contains(it) }) return false
        val path = url.substringBefore("?").lowercase()
        if (Constants.DIRECT_STREAM_SUFFIXES.any { path.endsWith(it) }) return true
        // query 携带媒体标记也算命中（宽松判断）；
        // 不含易误报的 .ts（如 xxx.types、font.ts）
        return MEDIA_QUERY_MARKERS.any { lowerUrl.contains(it) }
    }

    companion object {
        private const val TAG = "WebSniffer"

        /** 嗅探默认超时（毫秒） */
        private const val SNIFF_TIMEOUT_MS = 15_000L

        /** data:/blob: 等超长内联地址不做媒体判断 */
        private const val MAX_CHECK_URL_LENGTH = 4096

        /** 手机 Chrome UA，兼容大部分网页解析站 */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** query 中携带即算媒体地址的标记（不含易误报的 .ts） */
        private val MEDIA_QUERY_MARKERS = listOf(".m3u8", ".mp4", ".flv")

        /** 解析失败占位媒体特征（yparse 站 404 提示片等），不作为嗅探命中 */
        private val PLACEHOLDER_MARKERS = listOf("/404_", "yparse")
    }
}
