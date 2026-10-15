// chaquo：Chaquopy 内嵌 Python 运行时（Android 端 PY 爬虫引擎）。
// 独立纯 AGP 模块——Chaquopy 插件与 KMP androidTarget 不兼容，经 AAR 被 composeApp androidMain 消费。
// catvod 桥类（com.github.catvod.*，FQN 冻结）编译期经 compileOnly(:bridge) 可见，
// 运行时由宿主 APK 提供（composeApp androidMain/java 同源码编入），双份不重复打包。

plugins {
    id("com.android.library")
    id("com.chaquo.python")
}

android {
    namespace = "com.fongmi.chaquo"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // 与 composeApp splits 对齐：真机 arm64/v7a + 模拟器 x86_64
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Chaquopy Kotlin DSL（python 版本与 pip 依赖配置；python 块须在顶层 chaquopy 扩展内）
chaquopy {
    defaultConfig {
        // drpy 生态基线（FongMi 同款）；Chaquopy 16.x 支持区间内
        version = "3.10"
        pip {
            // lxml/pyquery/bs4 等解析栈 + pycryptodome 加解密（drpy 脚本依赖）；
            // index 走构建机 pip 全局配置（repository DSL 为 17.x 新增，16.x 不支持）
            install("-r", "requirements.txt")
        }
    }
}

dependencies {
    // 仅编译期：运行时宿主 APK 提供（避免 android 桩类与真 framework 冲突进包）
    compileOnly(project(":bridge"))
    implementation("com.google.code.gson:gson:2.10.1")
}
