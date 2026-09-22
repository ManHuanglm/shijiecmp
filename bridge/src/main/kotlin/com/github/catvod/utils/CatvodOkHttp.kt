package com.github.catvod.utils

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 爬虫 HTTP 桥接（仿 FongMi/TV 工具类）：桌面原型用本地 OkHttpClient 复刻宿主
 * RetrofitClient 行为（浏览器 UA + 以请求地址为 Referer）。所有方法同步执行，
 * 失败兜底返回空值，不向 jar 抛异常。
 */
class OkHttp private constructor() {

    companion object {

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/114.0.0.0 Mobile Safari/537.36"

        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        private val httpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
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
            // 设置页配置的 HTTP 代理（host:port，重启后生效；仅对爬虫桥接请求生效）。
            // 经 CatvodHooks 注入解析函数，解耦桥接层与宿主 AppContainer（避免反向依赖）
            CatvodHooks.httpProxyResolver()?.let { config ->
                val host = config.substringBefore(':').trim()
                val port = config.substringAfter(':', "").trim().toIntOrNull() ?: 0
                if (host.isNotBlank() && port in 1..65535) {
                    val address = java.net.InetSocketAddress(host, port)
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, address)
                    builder.proxy(proxy)
                }
            }
            builder.build()
        }

        @JvmStatic
        fun client(): OkHttpClient = httpClient

        // region GET

        @JvmStatic
        fun newCall(url: String?): Response = newCall(url, emptyMap<String, String>())

        @JvmStatic
        fun newCall(url: String?, headers: Map<String, String>?): Response =
            client().newCall(buildGetRequest(url, headers)).execute()

        @JvmStatic
        fun newCall(request: Request): Response = client().newCall(request).execute()

        @JvmStatic
        fun string(url: String?): String = string(url, emptyMap<String, String>())

        @JvmStatic
        fun string(url: String?, headers: Map<String, String>?): String = runCatching {
            newCall(url, headers).use { response -> response.body?.string().orEmpty() }
        }.getOrDefault("")

        @JvmStatic
        fun string(request: Request): String = runCatching {
            client().newCall(request).execute().use { response -> response.body?.string().orEmpty() }
        }.getOrDefault("")

        // endregion

        // region POST

        /** 表单 POST（params 为键值对），失败返回空串 */
        @JvmStatic
        fun post(url: String?, params: Map<String, String>?): String = post(url, params, emptyMap<String, String>())

        @JvmStatic
        fun post(url: String?, params: Map<String, String>?, headers: Map<String, String>?): String = runCatching {
            val form = FormBody.Builder().apply {
                params?.forEach { (key, value) -> add(key, value) }
            }.build()
            post(url, form, headers)
        }.getOrDefault("")

        /** 原始报文 POST（默认 JSON Content-Type），失败返回空串 */
        @JvmStatic
        fun post(url: String?, body: String?): String = post(url, body, emptyMap<String, String>())

        @JvmStatic
        fun post(url: String?, body: String?, headers: Map<String, String>?): String = runCatching {
            val payload = (body ?: "").toRequestBody(JSON_TYPE)
            post(url, payload, headers)
        }.getOrDefault("")

        @JvmStatic
        fun post(url: String?, body: okhttp3.RequestBody?, headers: Map<String, String>?): String = runCatching {
            val request = buildRequest(url, "POST", body ?: emptyFormBody(), headers)
            client().newCall(request).execute().use { response -> response.body?.string().orEmpty() }
        }.getOrDefault("")

        // endregion

        // region 下载

        /** 下载到指定文件（先写临时文件再改名），成功返回 true */
        @JvmStatic
        fun download(url: String?, file: File?): Boolean = runCatching {
            val target = file ?: return false
            target.parentFile?.mkdirs()
            val temp = File(target.absolutePath + ".tmp")
            newCall(url, null).use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                body.byteStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (temp.length() <= 0L) return false
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }.getOrDefault(false)

        // endregion

        private fun buildGetRequest(url: String?, headers: Map<String, String>?): Request =
            buildRequest(url, "GET", null, headers)

        private fun buildRequest(url: String?, method: String, body: okhttp3.RequestBody?, headers: Map<String, String>?): Request {
            val builder = Request.Builder().url(url.orEmpty())
            when (method) {
                "POST" -> builder.post(body ?: emptyFormBody())
                else -> builder.get()
            }
            headers?.forEach { (key, value) -> builder.header(key, value) }
            return builder.build()
        }

        private fun emptyFormBody(): okhttp3.RequestBody = FormBody.Builder().build()
    }
}
