pluginManagement {
    repositories {
        // 阿里云镜像优先，官方仓库兜底（与原工程 shiping 保持一致）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // dex2jar 组构件在本机镜像存在 pom 可用但 jar 缺失的间歇性问题，强制仅从 Maven Central 解析
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("de.femtopedia.dex2jar") }
        }
        // Chaquopy CPython 运行时（com.chaquo.*）：本机腾讯/阿里镜像对 .so 大文件回源期间
        // 返回 5xx，Gradle 对连接失败直接放弃整轮解析（不走后续仓库兜底）；repo1 直连稳定
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroupByRegex("com\\.chaquo\\..*") }
        }
        // unidbg（jitpack 组）：镜像不代理 jitpack，直连避免回源抖动
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeGroup("com.github.zhkl0228.unidbg") }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ShijieCmp"
include(":bridge")
include(":shared")
include(":composeApp")
// Chaquopy Python 引擎模块（Android 端 PY 爬虫运行时；Chaquopy 插件仅支持纯 AGP 模块，独立于 KMP）
include(":chaquo")
