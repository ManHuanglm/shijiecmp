package android.media

/**
 * 桌面 stub：android.media.MediaCodecInfo 最小实现。
 * catvod 爬虫（如 Bili 站点播放器映射）引用此类做编解码能力判断，桌面端无硬件编解码语义，
 * 仅提供类型存在性与空能力集合，避免 NoClassDefFoundError。
 */
class MediaCodecInfo private constructor() {

    val name: String get() = "stub"

    val isEncoder: Boolean get() = false

    fun getCapabilitiesForType(type: String): CodecCapabilities = CodecCapabilities()

    class CodecCapabilities {

        val profileLevels: Array<ProfileLevel> = emptyArray()

        val audioCapabilities: AudioCapabilities? = null

        val videoCapabilities: VideoCapabilities? = null
    }

    class ProfileLevel(val profile: Int = 0, val level: Int = 0)

    class AudioCapabilities {
        val maxInputChannelCount: Int = 2
        fun getSupportedSampleRates(): IntArray = intArrayOf(44100, 48000)
    }

    class VideoCapabilities {
        val width: Int = 1920
        val height: Int = 1080
    }
}
