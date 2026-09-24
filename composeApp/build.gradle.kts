// composeApp：Compose Multiplatform UI 模块。
// commonMain 统一 UI（M3 起以桌面版为底）；androidMain 应用入口+Android 平台实现；
// desktopMain 桌面入口+JNA 播放内核装配。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    // Room 编译期处理（androidMain 数据栈，KSP 经 kspDebugKotlinAndroid/kspReleaseKotlinAndroid 配置）
    id("com.google.devtools.ksp")
}

dependencies {
    // Room 编译期处理：KMP android target 主编译的 KSP 配置（配置名 = ksp<编译任务后缀>，
    // 运行时动态创建须字符串注册；debug/release 两个变体分别注册）
}

// KSP 配置由插件在 source set 创建后生成（kspAndroid/kspAndroidDebug 等）：延迟注册
afterEvaluate {
    val kspConfigs = configurations.names.filter { it.startsWith("ksp") }
    kspConfigs.filter { it == "kspAndroid" || it == "kspAndroidDebug" || it == "kspAndroidRelease" }
        .forEach { name ->
            dependencies.add(name, "androidx.room:room-compiler:2.6.1")
        }
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            // 捆绑原生资源（mpv/vlc/mdk）进 jar：msi/exe 安装包仅打包 jars，
            // 安装版首次运行由 BundledNative 解压到数据目录后加载
            resources.srcDir("app-resources")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                // 探针/桌面代码编译期需要（运行时经 shared 传递提供）
                compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
                compileOnly("com.google.code.gson:gson:2.10.1")
            }
        }
        // cmpMain：Android/Desktop 共享 UI 中间源集（可见 shared jvmCommon 模型与接口，
        // 经 AppGraph 消费数据层；iOS/Wasm Phase3 时再评估上移 commonMain）
        val cmpMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(cmpMain)
        desktopMain.dependsOn(cmpMain)

        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        }
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.12.0")
            implementation("androidx.activity:activity-compose:1.8.2")
            // 扫码页协程生命周期作用域（lifecycleScope）
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
            // 爬虫运行时（jar 下载）与 CMS 接口（Retrofit/ApiService）
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            // catvod 桥 OkDns.java 编译需要（shared 为 implementation 不传递）
            implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
            implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
            // Android 代理路由（AndroidProxyRoutes）实现 SpiderProxyBridge 签名需 NanoHTTPD 类型
            implementation("org.nanohttpd:nanohttpd:2.3.1")
            implementation("com.squareup.retrofit2:retrofit:2.9.0")
            implementation("com.squareup.retrofit2:converter-gson:2.9.0")
            // 播放内核（M4）：media3 ExoPlayer + HLS/DASH
            implementation("androidx.media3:media3-exoplayer:1.5.1")
            implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
            implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
            implementation("androidx.media3:media3-ui:1.5.1")
            implementation("androidx.media3:media3-common:1.5.1")
            // 下载导出（M5）：Transformer 把 HLS 转封装为 MP4（InAppMuxer 需 muxer）
            implementation("androidx.media3:media3-transformer:1.5.1")
            implementation("androidx.media3:media3-muxer:1.5.1")
            // 扫码（M5）：CameraX 取景 + zxing 解码 TVBox 配置二维码
            implementation("androidx.camera:camera-camera2:1.3.4")
            implementation("androidx.camera:camera-lifecycle:1.3.4")
            implementation("androidx.camera:camera-view:1.3.4")
            implementation("com.google.zxing:core:3.5.3")
            // 持久化：Room（仓库栈）+ DataStore（KeyValueStore 实现）
            implementation("androidx.room:room-runtime:2.6.1")
            implementation("androidx.room:room-ktx:2.6.1")
            implementation("androidx.datastore:datastore-preferences:1.0.0")
        }
    }
}

android {
    namespace = "com.shiping.app"
    compileSdk = 36

    sourceSets {
        // KGP androidTarget 不自动注册 androidMain 的 Java 目录：catvod 桥（FQN 冻结，
        // payload 爬虫 jar 经 DexClassLoader parent-first 解析宿主类）必须由 javac 编入 APK
        getByName("main") {
            java.srcDirs("src/androidMain/java")
        }
    }

    defaultConfig {
        applicationId = "com.shiping.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // 签名信息仅从 CI 环境变量注入，禁止在版本库中硬编码密码；
            // 本地缺少环境变量时由 buildTypes 回退 debug 签名
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val storePass = System.getenv("KEYSTORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")
            if (keystorePath != null && storePass != null && alias != null && keyPass != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 本地无 CI 签名环境变量时回退 debug 签名，保证 assembleRelease 仍可构建安装
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

compose.desktop {
    application {
        // 主入口可经 -Pcmp.mainClass=... 覆盖（如 SpiderProbeKt 探针），默认正式入口
        mainClass = (findProperty("cmp.mainClass") as String?) ?: "com.shiping.desktop.MainKt"
        // Windows 控制台 UTF-8；jna.encoding 决定 JNA String 参数（含中文播放地址）编解码
        jvmArgs += listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djna.encoding=UTF-8")

        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "ShijieCmp"
            packageVersion = "1.0.0"
            vendor = "shijie"
            // 捆绑资源：mpv/（libmpv-2.dll）、vlc/（libvlc.dll+plugins+lua）、mdk/（mdk.dll+ffmpeg-9.dll+libass.dll）
            appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))
            // jlink 模块显式声明（照抄原工程清单）：java.desktop(Compose/Swing 播放窗口)、
            // java.sql(SQLite JDBC)、jdk.unsupported(dex2jar/ASM/JNA)；勿用 includeAllModules
            modules(
                "java.base", "java.desktop", "java.logging", "java.management", "java.naming",
                "java.sql", "java.xml", "java.security.jgss", "jdk.unsupported",
                "jdk.crypto.ec", "jdk.crypto.mscapi", "jdk.zipfs",
            )
            windows {
                menu = true
                shortcut = true
                dirChooser = true
            }
        }
    }
}

// 捆绑资源同步兜底（照抄原工程方案）：插件 1.6.11 在 Gradle 8.14 下 prepareAppResources
// 判定 NO-SOURCE，mpv/mdk 内核不会进包。finalizedBy 在 jpackage 产物落盘后把
// app-resources 直接镜像到 <app-image>/app/resources（cfg 中 compose.application.resources.dir），
// Sync 幂等镜像，重复构建安全。
val syncBundledNativeResources = tasks.register<Sync>("syncBundledNativeResources") {
    from(project.layout.projectDirectory.dir("app-resources"))
    into(layout.buildDirectory.dir("compose/binaries/main/app/ShijieCmp/app/resources"))
}

tasks.matching { it.name == "createDistributable" || it.name == "packageDistributionForCurrentOS" }.configureEach {
    finalizedBy(syncBundledNativeResources)
}
