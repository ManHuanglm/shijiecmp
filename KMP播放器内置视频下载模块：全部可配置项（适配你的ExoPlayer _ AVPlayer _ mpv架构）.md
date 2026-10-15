# KMP播放器内置视频下载模块：全部可配置项（适配你的ExoPlayer / AVPlayer / mpv架构）

# KMP 播放器内置视频下载模块：全部可配置项（适配你的 ExoPlayer / AVPlayer /mpv 架构）

> 分为【全局下载管理器配置】、【单个任务配置】、【网络策略】、【存储策略】、【HLS/DASH 流媒体专属配置】、【状态回调】，可以直接封装成 `DownloadConfig` / `DownloadTaskConfig` expect/actual 类。

## 一、全局管理器配置（单例，App 全局生效）

> Android 使用 ExoPlayer DownloadManager；iOS AVPlayer 离线下载；公共逻辑放在 KMP common 层。

1. \*\*最大并发任务数 \*\***`maxParallelDownloads`**
   - 含义：同时跑几个下载任务，排队任务进入队列。ExoPlayer 默认推荐`1~3`，太高容易被 CDN 限流。
2. \*\*磁盘最低预留空间 \*\***`minFreeDiskBytes`**
   - 剩余空间低于该值，禁止新建下载任务。例：`1GB`。
3. \*\*全局最大缓存总容量 \*\***`maxCacheTotalBytes`**
   - 所有离线视频总和上限，超出自动清理旧任务（LRU 淘汰策略）。
4. \*\*默认保存根目录 \*\***`defaultSaveRootDir`**
   - Android：`filesDir` / `cacheDir`；iOS：`ApplicationSupport`；公共层只定义路径字符串，actual 实现获取真实路径。
5. \*\*网络约束 \*\***`networkConstraint`**
   - `wifiOnly: Boolean`：仅 WiFi 下自动下载，移动网络暂停排队任务。
   - `allowRoaming: Boolean`：漫游网络是否允许下载（iOS）。
6. \*\*全局限速 \*\***`globalSpeedLimitBps`**
   - 全局下载带宽上限，0 = 不限速。
7. **任务自动重试** **`globalRetryCount`**、\*\*\*\*`retryDelayMs`
   - 分片 / HTTP 失败重试次数、重试间隔；网络抖动自动重试。
8. \*\*自动清理策略 \*\***`autoCleanOrphan`**
   - 开启：启动时扫描残留临时分片，清理废弃文件。
9. **通知开关（平台 actual）**
   - Android：系统通知展示进度；iOS 后台下载通知。

## 二、单个下载任务配置（每个视频独立）

```kotlin
data class DownloadTaskConfig(
    val taskId: String,
    val mediaUrl: String,
    val title: String,
    // 音视频轨道选择（HLS多码率）
    val videoBitrate: Long?, // 选指定码率，null=自动选最高
    val audioLanguage: String?,
    val subtitleLanguages: List<String>,
    // 文件存储
    val saveFileName: String,
    val customSaveDir: String?, // 覆盖全局目录，null使用全局
    // 鉴权
    val headers: Map<String, String>, // Cookie、token、UA
    val queryParams: Map<String, String>,
    // 任务行为
    val priority: Int, // 队列优先级，高优先下载
    val enableResume: Boolean, // 是否开启断点续传
    val speedLimitBps: Long, // 单任务独立限速，覆盖全局
    // HLS专属
    val mergeToMp4: Boolean, // 是否下载完成后合并分片输出mp4
    val keepTempSegments: Boolean, // 完成后是否保留ts分片
)
```

- 任务状态：`QUEUED / DOWNLOADING / PAUSED / COMPLETED / FAILED / CANCELED`
- 进度回调：已下载字节、总字节、分片序号、速度 bps

## 三、网络相关设置

1. 超时：`connectTimeoutMs`、`readTimeoutMs`（分片请求超时）
2. HTTP 方法、自定义请求头（token、cookie、Referer 防盗链）
3. 重定向策略：是否跟随 301/302
4. 分片并发数（HLS）：单 m3u8 同时请求分片数量，**不要过大，防止 429 限流**
5. 证书校验开关（测试环境使用，生产必须开启）

## 四、存储 & 文件管理设置

1. 存储类型区分：
   - 临时目录：分片下载缓存（失败自动删除）
   - 正式目录：合并完成后的视频文件
2. 文件权限：Android ScopedStorage /iOS Sandbox
3. 任务数据库：持久化任务列表、进度、元信息（Room / Realm，KMP 可使用 SQLDelight）
4. 媒体元数据保存：封面、时长、标题，供播放器列表读取
5. 删除策略：
   - 删除任务：仅删除数据库记录 / 删除本地媒体文件二选一

## 五、HLS / DASH 流媒体专属配置（重点，m3u8 场景）

1. 多码率自适应选择：最高 / 指定分辨率 / 指定码率
2. AES-128 加密：密钥获取地址、密钥请求头、密钥缓存
3. 分片校验：分片 MD5 校验开关，防止损坏分片
4. 分片合并：
   - 模式 1：保留 m3u8+ts 分片（ExoPlayer 原生离线播放，不需要转码，速度最快）
   - 模式 2：下载完成自动合并为单个 MP4（需要 ffmpeg/mpv 混流）
5. 跳过已存在分片：断点续传核心，校验分片文件大小，跳过已下载分片

> ⚠️ ExoPlayer 原生离线下载：**不转码，保存 m3u8 + 分片**，适合直接本地播放；如果你要输出独立 MP4，需要额外 mpv/ffmpeg 混流。

## 六、下载任务回调 & 事件设置

- onProgress：进度、瞬时下载速度
- onCompleted：下载成功
- onPaused：手动 / 网络自动暂停
- onFailed：错误码（磁盘不足、鉴权失败、网络、密钥拉取失败）
- onCanceled：任务取消 + 临时文件清理

## 七、KMP 分层封装建议（和你播放器三模式架构对齐）

1. **Common 层**：
   - `DownloadManager` expect 类
   - 数据模型：`DownloadConfig`、`DownloadTask`、`DownloadState`
   - 业务接口：enqueue/pause/resume/cancel/getTasks
2. **Android actual**：
   - 封装 ExoPlayer `DownloadManager`
   - WorkManager 后台下载，系统通知
3. **iOS actual**：
   - AVAssetDownloadTask（AVPlayer 原生离线下载）
4. **Desktop actual**：
   - mpv + http 下载器，本地文件管理

## 八、用户侧 UI 可暴露的选项（播放器设置面板）

- [ ] 仅在 WiFi 下下载
- [ ] 最大并发下载数量（1/2/3）
- [ ] 下载完成自动合并为 MP4
- [ ] 下载时最大带宽限制
- [ ] 自动清理旧离线视频
- [ ] 默认下载清晰度

***

持久化下载任务

多切片同时下载
