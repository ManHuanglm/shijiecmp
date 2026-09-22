package com.shiping.desktop.data.spider

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 共享 OkHttp 客户端（对齐 app RetrofitClient 的超时与连接池配置）。
 * 信任所有证书：TVBox 生态大量配置站/图床证书不规范（自签名、域名不匹配、
 * 老协议），严格校验会直接失败（对齐 FongMi CatvodOkHttp 的做法）。
 */
object SpiderHttp {

    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        runCatching { trustAll(builder) }.getOrElse { builder }
            .build()
    }

    /** 信任所有证书并放行任意主机名（仅用于公开网络资源的拉取与播放） */
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
