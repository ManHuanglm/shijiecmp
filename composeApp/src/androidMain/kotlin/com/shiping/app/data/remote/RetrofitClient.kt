package com.shiping.app.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shiping.app.BuildConfig
import com.shiping.app.util.Constants
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    /** 占位基地址，实际请求通过 @Url 动态指定 */
    private const val BASE_URL = "http://hongniuzy2.com/"

    private val gson: Gson by lazy {
        GsonBuilder()
            .setLenient()
            .create()
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 伪装浏览器 UA + 以请求地址为 Referer：部分资源站校验 UA 或按 Referer 防盗链
            .addInterceptor { chain ->
                val request = chain.request()
                val referer = "${request.url.scheme}://${request.url.host}/"
                chain.proceed(
                    request.newBuilder()
                        .header("User-Agent", Constants.API_USER_AGENT)
                        .header("Referer", referer)
                        .build(),
                )
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}
