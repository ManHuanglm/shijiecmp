package com.shiping.desktop.ui.player

/**
 * 画面调节模式（跨端共享）：id 为内核属性/持久化键值（对齐桌面 setVideoScale 既有语义，
 * KEY_VIDEO_SCALE 持久化跨端共享），ratio 为固定比例容器值（Android 布局层用，null=非固定比例）。
 */
enum class VideoScaleMode(val id: String, val label: String, val ratio: Float?) {
    FIT("fit", "自动", null),
    R16_9("16:9", "16:9", 16f / 9f),
    R9_16("9:16", "9:16", 9f / 16f),
    R4_3("4:3", "4:3", 4f / 3f),
    CROP("crop", "裁剪", null),
    STRETCH("stretch", "铺满", null),
    ;

    companion object {
        /** 按 id 解析（未知值回退自动） */
        fun fromId(raw: String): VideoScaleMode = entries.firstOrNull { it.id == raw } ?: FIT

        /** 下一模式（设置面板循环切换用） */
        fun nextOf(current: VideoScaleMode): VideoScaleMode = entries[(current.ordinal + 1).mod(entries.size)]
    }
}
