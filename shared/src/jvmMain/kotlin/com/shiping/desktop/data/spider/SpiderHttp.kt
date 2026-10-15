package com.shiping.desktop.data.spider

import com.google.gson.JsonParser
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
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

    /** DoH 兜底解析端点（阿里 Anycast，小众域名系统 DNS 抖动时兜底） */
    private const val DOH_ENDPOINT = "https://dns.alidns.com/resolve"

    /** DoH 独立客户端（不走兜底 Dns，避免递归；简短超时控制失败请求延迟） */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** 系统 DNS 失败时的 DoH 兜底：小众域名（免费二级域等）本地解析易抖动 */
    private val fallbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrElse { systemError ->
                lookupOverHttps(hostname)?.let { return it }
                throw systemError
            }
    }

    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(fallbackDns)
        runCatching { trustAll(builder) }.getOrElse { builder }
            .build()
    }

    /** DoH JSON API 查询 A 记录（只取 type=1 的 IP 字面量，避免 CNAME 值再走系统 DNS），失败返回 null */
    private fun lookupOverHttps(hostname: String): List<InetAddress>? = runCatching {
        val url = DOH_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", "A")
            .build()
        val response = dohClient.newCall(Request.Builder().url(url).get().build()).execute()
        response.use {
            if (!it.isSuccessful) return@runCatching null
            val body = it.body?.string() ?: return@runCatching null
            val answer = JsonParser.parseString(body).asJsonObject.getAsJsonArray("Answer")
                ?: return@runCatching null
            answer.mapNotNull { element ->
                val entry = element.asJsonObject
                if (entry.get("type")?.asInt != 1) return@mapNotNull null
                runCatching { InetAddress.getByName(entry.get("data").asString) }.getOrNull()
            }.filter { !it.isLinkLocalAddress && !it.isMulticastAddress }
                .takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

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
