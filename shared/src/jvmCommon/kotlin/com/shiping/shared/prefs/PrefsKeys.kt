package com.shiping.shared.prefs

/**
 * 偏好键常量统一出口（jvmCommon）：键名与桌面 DesktopPrefs 完全一致，
 * 统一 UI（cmpMain）经 KeyValueStore + PrefsKeys 读写，两端存储实现各自映射
 * （桌面=Properties 文件，Android=DataStore）。
 */
object PrefsKeys {

    /** 当前选中的 API 源 ID */
    const val KEY_CURRENT_API_ID = "currentApiId"

    /** 当前选中的直播源 ID */
    const val KEY_CURRENT_TV_ID = "currentTvId"

    /** 直播收藏频道（favoriteKey 逗号分隔） */
    const val KEY_TV_FAVORITES = "tvFavorites"

    /** 默认解析源地址 */
    const val KEY_DEFAULT_PARSER_URL = "defaultParserUrl"

    /** 播放音量（0-100） */
    const val KEY_PLAYER_VOLUME = "playerVolume"

    /** 播放倍速 */
    const val KEY_PLAYER_SPEED = "playerSpeed"

    /** 自定义数据目录（持久化于默认目录的 prefs，重启生效） */
    const val KEY_DATA_DIR = "dataDir"

    /** 过滤列表中名称/备注含「切片」的条目 */
    const val KEY_FILTER_SLICE_AD = "filterSliceAd"

    /** m3u8 播放级广告切片过滤（Android media3 数据源与桌面本地代理 /m3u8 路由共用） */
    const val KEY_FILTER_M3U8_AD = "filterM3u8Ad"

    /** 播放中离开应用自动进画中画（Android 专属，默认开启） */
    const val KEY_PIP_ENABLED = "pipEnabled"

    /** 播放记录开关（关闭后不保存播放进度） */
    const val KEY_PLAY_RECORD_ENABLED = "playRecordEnabled"

    /** 触感提示（桌面端无振动，仅保存设置） */
    const val KEY_TOUCH_HINT = "touchHint"

    /** HTTP 代理（host:port，对爬虫网络请求生效，重启生效） */
    const val KEY_HTTP_PROXY = "httpProxy"

    /** mpv 硬解码 */
    const val KEY_MPV_HWDEC = "mpvHwdec"

    /** mpv 特效字幕（保留 ASS 特效） */
    const val KEY_MPV_FX_SUBTITLE = "mpvFxSubtitle"

    /** 字幕颜色（#RRGGBB） */
    const val KEY_SUB_COLOR = "subColor"

    /** 字幕背景颜色（#RRGGBB） */
    const val KEY_SUB_BACK_COLOR = "subBackColor"

    /** 字幕背景色透明度（0-100） */
    const val KEY_SUB_BACK_ALPHA = "subBackAlpha"

    /** 字幕大小（百分比，50-200） */
    const val KEY_SUB_SIZE = "subSize"

    /** mpv 画面比例：fit=原比例 crop=裁剪铺满 stretch=拉伸 */
    const val KEY_VIDEO_SCALE = "videoScale"

    /** 显示上一集/下一集按钮 */
    const val KEY_SHOW_PREV_NEXT = "showPrevNext"

    /** 播放窗口最小化时暂停播放 */
    const val KEY_PAUSE_ON_BACKGROUND = "pauseOnBackground"

    /** 控制条显示总时长 */
    const val KEY_SHOW_TOTAL_TIME = "showTotalTime"

    /** 松手取消调整进度（移动端手势，仅保存设置） */
    const val KEY_SHOW_SEEK_CANCEL = "showSeekCancel"

    /** 全局倍速（浮点，起播默认速率） */
    const val KEY_GLOBAL_SPEED = "globalSpeed"

    /** 快进快退步长秒（0 不展示按钮） */
    const val KEY_SEEK_STEP = "seekStep"

    /** 长按倍速（移动端手势，仅保存设置） */
    const val KEY_LONG_PRESS_SPEED = "longPressSpeed"

    /** 多线程播放：分片大小 kb */
    const val KEY_M3U8_CHUNK_KB = "m3u8ChunkKb"

    /** 多线程播放：线程数 */
    const val KEY_M3U8_THREADS = "m3u8Threads"

    /** 多线程播放：已知晓风险并确认使用 */
    const val KEY_M3U8_RISK_ACK = "m3u8RiskAck"

    /** 主题模式：0=跟随系统 1=浅色 2=深色 */
    const val KEY_THEME_MODE = "theme_mode"

    /** 主题色名称（蓝/黄/青/红/绿/紫/橙） */
    const val KEY_THEME_COLOR = "theme_color"

    /** 界面语言（暂仅简体中文） */
    const val KEY_LANGUAGE = "language"

    /** 隐藏的侧边栏 Tab（逗号分隔名称，首页/设置不可隐藏） */
    const val KEY_HIDE_TABS = "hideTabs"

    /** 字体缩放比例（百分比，80-140） */
    const val KEY_FONT_SCALE = "fontScale"

    /** 字体（暂仅系统默认） */
    const val KEY_FONT_NAME = "fontName"

    /** 播放内核（设置页可选：mpv/vlc/mdk，桌面端专用） */
    const val KEY_PLAYER_KERNEL = "playerKernel"

    /** 当前生效的播放器内核 ID（与 KEY_PLAYER_KERNEL 同值，统一 UI 读写入口） */
    const val KEY_PLAYER_KERNEL_CURRENT = "playerKernel"

    /** 源使用历史（逗号分隔源 ID，最近使用在前，首页图标弹层快速切换） */
    const val KEY_API_SOURCE_HISTORY = "apiSourceHistory"

    /** 首页海报布局档位（0=四列 1=三列 2=单列大图 3=双列宽图 4=宽幅，PosterLayout.ordinal） */
    const val KEY_HOME_POSTER_LAYOUT = "homePosterLayout"

    /** 搜索结果海报布局档位（0=双列宽图 1=双列海报 2=单列列表，SearchPosterLayout.ordinal） */
    const val KEY_SEARCH_POSTER_LAYOUT = "searchPosterLayout"

    /** 首页分类顺序前缀（拼接源地址，值为逗号分隔 typeId） */
    const val KEY_HOME_CATEGORY_ORDER = "homeCategoryOrder_"

    /** 主导航 Tab 位置（bottom=底部导航 / drawer=左上抽屉） */
    const val KEY_TAB_POSITION = "mainTabPosition"

    /** 源列表加锁源查看密码（存 SHA-256 哈希，空=未设置，首次点击锁图标时设置） */
    const val KEY_SOURCE_LOCK_PASSWORD = "sourceLockPassword"

    /** 首页屏蔽分类（逗号分隔分类名，跨源生效，密码保护管理） */
    const val KEY_BLOCKED_CATEGORIES = "blockedCategories"

    /** 首页屏蔽分类管理密码（存 SHA-256 哈希，空=未设置，首次进入屏蔽页时设置） */
    const val KEY_BLOCKED_PASSWORD = "blockedCategoryPassword"

    /** 播放器：显示上一集/下一集按钮 */
    const val KEY_PLAYER_SHOW_PREV_NEXT = "playerShowPrevNext"

    /** 播放器：进入后台暂停播放 */
    const val KEY_PLAYER_PAUSE_ON_BACKGROUND = "playerPauseOnBackground"

    /** 播放器：时间显示总时长（false=仅当前时间） */
    const val KEY_PLAYER_SHOW_TOTAL_DURATION = "playerShowTotalDuration"

    /** 播放器：快进快退秒数（0=不展示快进快退按钮） */
    const val KEY_PLAYER_SEEK_STEP_SEC = "playerSeekStepSec"

    /** 播放器：长按倍速（占位，暂未接长按手势） */
    const val KEY_PLAYER_LONG_PRESS_RATE = "playerLongPressRate"

    /** 下载限速档位 KB/s（0=不限速；档位见 Constants.DOWNLOAD_SPEED_OPTIONS） */
    const val KEY_DOWNLOAD_SPEED_LIMIT_KB = "downloadSpeedLimitKb"

    /** 下载最大并发任务数（1-3，桌面引擎多 worker） */
    const val KEY_DOWNLOAD_MAX_PARALLEL = "downloadMaxParallel"

    /** HLS 分片并发下载数（1-6，过大易触发 CDN 429 限流） */
    const val KEY_DOWNLOAD_SEGMENT_CONCURRENCY = "downloadSegmentConcurrency"

    /** 仅 WiFi 下自动继续下载（Android 有效；桌面恒允许） */
    const val KEY_DOWNLOAD_WIFI_ONLY = "downloadWifiOnly"

    /** 搜索结果布局：groups=分组行 / sidebar=源侧栏+网格 / compact=源条+混排网格 */
    const val KEY_SEARCH_DISPLAY_MODE = "searchDisplayMode"
}
