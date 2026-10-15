package com.shiping.shared.clipboard

/**
 * 剪贴板内容分类器（双端共用纯逻辑）：
 * 把剪贴板文本识别为可执行动作——配置链接/多仓 JSON 内容/视频直链/直播 m3u/搜索片名。
 */
sealed class ClipboardAction {

    /** 配置链接（单仓或多仓地址，导入后按 warehouses 展开批量添加） */
    data class ConfigUrl(val url: String) : ClipboardAction()

    /** 多仓配置 JSON 原文（用户直接粘贴的内容：批量添加仓库） */
    data class ConfigRaw(val content: String, val label: String) : ClipboardAction()

    /** m3u8/mp4/mkv 视频直链 */
    data class VideoDirect(val url: String) : ClipboardAction()

    /** m3u 直播列表地址 */
    data class LiveM3u(val url: String) : ClipboardAction()

    /** 普通文字：按片名搜索 */
    data class SearchText(val keyword: String) : ClipboardAction()

    /** 无可执行动作 */
    data object None : ClipboardAction()
}

object ClipboardClassifier {

    /** URL 提取（http(s)/clan/file 三协议；容忍首尾标点） */
    private val URL_REGEX = Regex("(?:(?:https?://)|(?:clan://(?:localhost/)?(?:[A-Za-z]:/)?))[^\\s\"'<>]+")

    /** 视频直链后缀（查询串之前判断） */
    private val VIDEO_EXT = Regex("\\.(m3u8|mp4|mkv)([?#].*)?$", RegexOption.IGNORE_CASE)

    /** 直播列表后缀（.m3u 非 .m3u8） */
    private val M3U_EXT = Regex("\\.m3u([?#].*)?$", RegexOption.IGNORE_CASE)

    /** 多行 URL（≥2 行纯链接）按批量仓库合成为多仓 JSON 内容 */
    fun classify(raw: String): ClipboardAction {
        val text = raw.trim()
        if (text.isEmpty()) return ClipboardAction.None
        // 口令识别：含「口令」字样的文本提取首个 URL 走配置导入（生态口令习惯：影视仓口令/OK口令等）
        val urls = URL_REGEX.findAll(text)
            .map { it.value.trimEnd(',', '，', ';', '；', ')', '）', '。', '"') }
            .filter { it.length > 8 }
            .toList()
        if (text.contains("口令") && urls.isNotEmpty()) {
            return ClipboardAction.ConfigUrl(urls.first())
        }
        if (urls.size >= 2) {
            // 多行链接 → 合成多仓 JSON（批量添加仓库）
            val json = buildString {
                append("{\"urls\":[")
                urls.forEachIndexed { i, url ->
                    if (i > 0) append(",")
                    append("{\"name\":\"仓库${i + 1}\",\"url\":\"$url\"}")
                }
                append("]}")
            }
            return ClipboardAction.ConfigRaw(json, "多仓链接")
        }
        if (urls.size == 1) {
            return classifyUrl(urls.first())
        }
        // 多仓 JSON 内容直接粘贴（含 urls/warehouse/storeHouse 键）
        val t = text.removePrefix("\uFEFF")
        if (t.startsWith("{") &&
            (t.contains("\"urls\"") || t.contains("\"warehouse\"") || t.contains("\"storeHouse\""))
        ) {
            return ClipboardAction.ConfigRaw(t, "多仓配置")
        }
        // 普通文字：按片名搜索（长度限制防误弹）
        val keyword = text.replace(Regex("\\s+"), " ").trim()
        if (keyword.length in 2..40) return ClipboardAction.SearchText(keyword)
        return ClipboardAction.None
    }

    private fun classifyUrl(url: String): ClipboardAction = when {
        VIDEO_EXT.containsMatchIn(url) -> ClipboardAction.VideoDirect(url)
        M3U_EXT.containsMatchIn(url) -> ClipboardAction.LiveM3u(url)
        else -> ClipboardAction.ConfigUrl(url)
    }
}
