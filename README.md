# ShijieCmp（视界）

基于 **Kotlin Multiplatform + Compose Multiplatform** 的双端影视聚合播放器，一套 `cmpMain` 共享 UI 同时驱动 **Android** 与 **Windows 桌面**，兼容 TVBox 配置与爬虫源生态（jar / JS / PY 三类源）。

## 功能特性

- **点播聚合**：TVBox 配置导入（多仓库源），首页分类浏览、详情多线路、选集播放、跨源搜索；首页/搜索多档海报布局，首页弹层快速切换最近使用的源
- **爬虫源三引擎**：
  - **jar 源**：Android 经 `DexClassLoader` 直接加载；桌面先经 dex2jar 转换（ASM 清洗伪修饰构造器）再加载
  - **JS 源**：QuickJS 引擎（FongMi 移植），双端运行，内置 cat.js / cheerio / crypto-js 等运行库
  - **PY 源**：Chaquopy 内嵌 CPython（Android），drpy 脚本按约定调用 Spider 桥
- **直播**：TVBox 直播源播放，频道切换
- **网页嗅探**：`parse=1` 源经内嵌 WebView 嗅探真实播放地址
- **播放内核四选一**：MPV / VLC / MDK（JNA 绑定原生内核）+ Media3/ExoPlayer，切换后下次播放生效
- **切片广告过滤**：列表级 + m3u8 播放级双重过滤（URL 关键词、`DISCONTINUITY` 启发式检测、目录指纹规则，可处理加密广告分片）；Android media3 数据源与桌面本地代理 `/m3u8` 路由共用 `filterSliceAd` 开关
- **收藏与历史**：Room 持久化，断点续播、追更记录
- **B 站源支持**：清晰度切换（qn）+ 扫码登录（zxing 生成二维码）
- **下载导出（双端）**：Android 经 Media3 Transformer 将 HLS 转封装为 MP4 并入相册；桌面由独立下载引擎承载，导航「下载」Tab 双端可用
- **剪贴板导入**：检测剪贴板中的 TVBox 配置链接，一键导入
- **本地包**：读取 `/sdcard/TVBoxOSC` 共享存储约定目录（Android 11+ 授予「所有文件访问」）
- **局域网投屏接收**：接收外部推送的直链并在独立播放窗承载
- **导航自定义**：8 个 Tab（首页/搜索/直播/收藏/历史/下载/我的/设置）可配置显隐与位置（底部导航 / 左上抽屉）
- **Android 专属**：扫码导入配置（CameraX + ZXing）、画中画（PiP）

## 技术架构

```text
├── bridge        纯 JVM 桥接层：catvod Java/Kotlin 桥 + android.* 桩 + FtyGuardEngine（unidbg）
│   ├── quickjs   com.fongmi.quickjs JS 引擎源 + PayloadPatch（ASM 注入 proxyInvoke 桥方法）
│   └── resources js/py 爬虫运行库（cat.js、cheerio、runner.py 等）
├── chaquo        Chaquopy PY 引擎模块（纯 AGP，独立于 KMP；内嵌 CPython + pip 依赖）
├── shared        KMP 数据 + 引擎层
│   ├── commonMain  纯 Kotlin 接口（PlayerKernel、clipboard 等）
│   ├── jvmCommon   Android/JVM 共享中间源集（gson/okhttp/nanohttpd、TVBox 解析、下载抽象）
│   ├── androidMain DexClassLoader 爬虫 jar 运行时
│   └── jvmMain     dex2jar 爬虫转换、unidbg、JNA 播放内核绑定（mpv/vlc/mdk）、
│                   QuickJS wrapper（libquickjs）、桌面下载引擎、NanoHTTPD 代理
└── composeApp    Compose Multiplatform UI
    ├── cmpMain     Android/Desktop 共享 UI（首页/搜索/直播/详情/下载/我的/设置、player 组件）
    ├── androidMain 应用入口 + Android 平台实现（Media3、CameraX、下载、投屏、画中画）
    └── desktopMain 桌面入口 + JNA 播放内核装配
```

核心链路：

- **爬虫源**：jar 源 Android `DexClassLoader` 直载 / 桌面 dex2jar 转换；JS 源走 QuickJS（桌面 native 为 `app-resources/quickjs.dll`，Android wrapper aar 自带各 ABI so）；PY 源走 Chaquopy
- **播放流量**：NanoHTTPD 本地代理（SpiderProxyServer）统一路由，桌面 `/m3u8` 路由承接广告过滤
- **W^X 规避**：`targetSdk=28`（`untrusted_app_27` 域）保留爬虫 go 代理二进制 exec 权限，TVBox 生态通用做法

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言/框架 | Kotlin 1.9.24、Compose Multiplatform 1.6.11、AGP 8.9.2、JDK 17 |
| 数据 | Room 2.6.1、DataStore Preferences |
| 网络 | OkHttp 4.12、Retrofit 2.9、Gson 2.10.1、Jsoup |
| 播放 | Media3 1.5.1（Android）、libmpv / libvlc / MDK via JNA（桌面） |
| 爬虫引擎 | DexClassLoader、dex2jar + ASM 9.7.1、QuickJS wrapper 3.2.3、Chaquopy 16.1.0、unidbg、BouncyCastle、SQLite JDBC |
| 其他 | NanoHTTPD（本地代理/投屏）、CameraX + ZXing（扫码）、material2 pullRefresh |

## 构建

### 环境要求

- JDK 17
- Android SDK（compileSdk 36 / minSdk 24 / targetSdk 28）
- Windows 10+（桌面打包；macOS/Linux 可运行开发调试但安装包产物仅配置了 MSI/EXE）

### Android APK

```bash
# Release（R8 混淆；无 CI 签名环境变量时自动回退 debug 签名，可直接安装）
gradlew :composeApp:assembleRelease
# 产物：composeApp/build/outputs/apk/release/
# ABI 分包：armeabi-v7a / arm64-v8a / x86_64 + universal 全架构合并包
```

签名发布：设置 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 环境变量后构建，即用正式签名。

> `targetSdk` 固定 28（低于 29 规避 Android 10+ W^X 限制），属爬虫 go 代理 exec 的必要条件，非疏漏。

### Windows 桌面安装包

```bash
# MSI + EXE（jpackage，自动捆绑 mpv/vlc/mdk 原生内核与 quickjs.dll）
gradlew :composeApp:packageDistributionForCurrentOS
# 产物：composeApp/build/compose/binaries/main/msi/ 与 exe/
```

> 播放内核原生件（`mpv/`、`vlc/`、`mdk/`）不入库，需置于 `composeApp/app-resources/` 后本地打包；CI 产物不含内核（quickjs.dll 体积小已入库，JS 源开箱可用）。

### 开发调试

```bash
gradlew :composeApp:run          # 启动桌面版
gradlew :composeApp:assembleDebug # Android Debug APK
gradlew :shared:jvmTest :bridge:test # 单元测试
```

## CI

GitHub Actions（[.github/workflows/ci.yml](.github/workflows/ci.yml)）：

- **test**：单元测试（bridge + shared jvm）+ Android Debug APK 构建
- **desktop-package**：Windows 桌面 EXE 打包并上传 Artifact（保留 7 天，不含播放内核）

## 免责声明

本项目仅供学习交流，聚合能力依赖第三方 TVBox 配置与爬虫源，请自行确保内容来源合法合规，与本项目无关。
