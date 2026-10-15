// ShijieCmp 根构建：KMP+CMP 统一工程（Android/iOS/Desktop/Web 目标渐进启用）。
// 本工程与原 shiping 工程并行存在，代码自原工程只读拷贝而来。

plugins {
    id("com.android.application") version "8.9.2" apply false
    // KMP android library 插件随 AGP 发布，shared 模块使用
    id("com.android.kotlin.multiplatform.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.multiplatform") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    // Compose Multiplatform（composeApp 使用；与 Kotlin 1.9.24 配套）
    id("org.jetbrains.compose") version "1.6.11" apply false
    // Chaquopy：Android 内嵌 Python（PY 爬虫引擎）。17.x 要求 AGP>=8.10，本项目 AGP 8.9.2 取 16.1.0（支持 8.0-8.13）
    id("com.chaquo.python") version "16.1.0" apply false
}
