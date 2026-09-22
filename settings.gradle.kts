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
