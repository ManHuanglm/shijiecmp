package com.shiping.app.data.spider

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android 爬虫运行时 HTTP 客户端（jar 下载/MD5 清单/TVBox 订阅配置拉取）：
 * - 对齐桌面 SpiderHttp 信任所有证书：TVBox 生态大量配置站/图床证书不规范
 *   （自签名、域名不匹配、老协议），严格校验会直接失败（含订阅导入链路）
 * - UA 仅在请求未显式设置时注入浏览器 UA：订阅导入 fetch 特意以 okhttp UA
 *   请求（部分配置站按 UA 区分配置文本/HTML 页面），interceptor 不得覆盖
 * - Referer 自动补全请求源站；超时语义 15/30/30s
 */
object SpiderHttp {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/114.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = runCatching { trustAll(baseBuilder()) }
        .getOrElse { baseBuilder() }
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            // 显式设置的 header（如 /hdr 播放注入的 Referer）不得覆盖，否则触发 CDN 防盗链 403
            if (request.header("Referer") == null) {
                builder.header("Referer", "${request.url.scheme}://${request.url.host}/")
            }
            if (request.header("User-Agent") == null) {
                builder.header("User-Agent", BROWSER_UA)
            }
            chain.proceed(builder.build())
        }
        .build()

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    /** 信任所有证书并放行任意主机名（仅用于公开网络资源的拉取与播放，对齐桌面端） */
    private fun trustAll(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val factory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(manager), SecureRandom())
        }.socketFactory
        return builder
            .sslSocketFactory(factory, manager)
            .hostnameVerifier { _, _ -> true }
    }
}
