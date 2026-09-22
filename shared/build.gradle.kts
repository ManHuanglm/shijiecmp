// shared：KMP 数据+引擎层。
// commonMain = 纯 Kotlin 接口；jvmCommon = android/jvm 共享中间源集（gson/okhttp/nanohttpd）；
// androidMain = DexClassLoader 爬虫运行时（M1）；jvmMain = dex2jar/unidbg/JNA 播放内核（M1/M2）。

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    androidLibrary {
        namespace = "com.shiping.shared"
        compileSdk = 36
        minSdk = 24
    }
    jvm()

    sourceSets {
        // jvmCommon：androidMain 与 jvmMain 的共享中间源集
        val jvmCommon by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmCommon)
        jvmMain.get().dependsOn(jvmCommon)

        commonMain.dependencies {
            // PlayerKernel 接口使用 StateFlow
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        }
        jvmCommon.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            // 本地代理服务（NanoHTTPD，SpiderProxyServer 桥接版）
            implementation("org.nanohttpd:nanohttpd:2.3.1")
            // 模型/TVBox 解析序列化（payload 爬虫生态绑定 gson 语义，不换 kotlinx-serialization）
            implementation("com.google.code.gson:gson:2.10.1")
            // 网络栈（对齐原工程；dnsoverhttps 为 catvod OkDns 桥接所需）
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
            implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
            // 部分爬虫 jar 依赖 jsoup
            implementation("org.jsoup:jsoup:1.17.2")
        }
        jvmMain.dependencies {
            // 爬虫生态桥接层：catvod Java/Kotlin 桥 + android.* 桩 + FtyGuardEngine（unidbg）
            // api 暴露：AppLog（bridge 内）被 UI 层直接引用；androidLibrary 插件下工程不可应用 java 插件，
            // 故 bridge 为独立纯 JVM 模块
            api(project(":bridge"))
            // dex -> jar 转换（Maven Central 维护分支，版本对齐 desktop-probe 验证结果）
            implementation("de.femtopedia.dex2jar:dex-tools:2.4.28")
            // JCE 扩展：payload 爬虫 AES/ECB/PKCS7Padding（JVM SunJCE 缺 PKCS7）
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            // unidbg 需在编译类路径可见（FtyGuardEngine 继承 AbstractJni，Kotlin 解析超类型用）
            implementation("com.github.zhkl0228.unidbg:unidbg-android:0.9.8")
            // android.database.sqlite 桩的底层实现（部分爬虫用 sqlite 缓存配置）
            implementation("org.xerial:sqlite-jdbc:3.46.1.3")
            // 播放内核 JNA 绑定（mpv libmpv-2.dll / MDK mdk.dll / VLC 经 vlcj 定位 libvlc）
            implementation("net.java.dev.jna:jna:5.13.0")
            implementation("uk.co.caprica:vlcj:4.7.3")
        }
    }
}

// 对齐工程统一 JVM target（本机 daemon 为 JDK 24，勿触发 toolchain 下载）
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}
