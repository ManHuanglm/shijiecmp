package com.shiping.app.data.spider

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Android 爬虫运行时 HTTP 客户端（jar 下载/MD5 校验清单拉取）：
 * 浏览器 UA + 请求地址 Referer；超时语义对齐桌面 SpiderHttp（15/30/30s）。
 */
object SpiderHttp {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/114.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val referer = "${request.url.scheme}://${request.url.host}/"
            chain.proceed(
                request.newBuilder()
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", referer)
                    .build(),
            )
        }
        .build()
}
