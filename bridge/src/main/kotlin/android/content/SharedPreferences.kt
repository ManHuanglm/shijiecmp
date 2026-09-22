package android.content

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面 stub：android.content.SharedPreferences 的磁盘持久化实现（catvod Prefers 使用）。
 * 每个 name 一份 properties 文件，存储目录由宿主通过 [MemSharedPreferences.storeDir] 注入。
 */
interface SharedPreferences {

    fun getAll(): Map<String, *>

    fun getString(key: String?, defValue: String?): String?

    fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>?

    fun getInt(key: String?, defValue: Int): Int

    fun getLong(key: String?, defValue: Long): Long

    fun getFloat(key: String?, defValue: Float): Float

    fun getBoolean(key: String?, defValue: Boolean): Boolean

    fun contains(key: String?): Boolean

    fun edit(): Editor

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)

    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)

    interface Editor {

        fun putString(key: String?, value: String?): Editor

        fun putStringSet(key: String?, values: MutableSet<String>?): Editor

        fun putInt(key: String?, value: Int): Editor

        fun putLong(key: String?, value: Long): Editor

        fun putFloat(key: String?, value: Float): Editor

        fun putBoolean(key: String?, value: Boolean): Editor

        fun remove(key: String?): Editor

        fun clear(): Editor

        fun commit(): Boolean

        fun apply()
    }

    interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    }
}

/** 磁盘持久化版 SharedPreferences（桌面 stub）：启动时载入，写入即落盘 */
class MemSharedPreferences private constructor(private val name: String) : SharedPreferences {

    private val values = ConcurrentHashMap<String, Any>()

    init {
        load()
    }

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key.orEmpty()] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val stored = values[key.orEmpty()] as? Set<*> ?: return defValues
        return stored.map { it.toString() }.toMutableSet()
    }

    override fun getInt(key: String?, defValue: Int): Int =
        (values[key.orEmpty()] as? Number)?.toInt() ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (values[key.orEmpty()] as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (values[key.orEmpty()] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key.orEmpty()] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key.orEmpty())

    override fun edit(): SharedPreferences.Editor = DiskEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private fun load() {
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val props = Properties()
            FileInputStream(file).use { props.loadFromXML(it) }
            for (key in props.stringPropertyNames()) {
                values[key] = decodeValue(props.getProperty(key))
            }
        }
    }

    private fun storeFile(): File? {
        val dir = storeDir ?: return null
        return File(dir, "$name.xml")
    }

    private fun persist() {
        val file = storeFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val props = Properties()
            for ((key, value) in values) props.setProperty(key, encodeValue(value))
            FileOutputStream(file).use { props.storeToXML(it, "shiping-desktop prefs") }
        }
    }

    /** 值序列化：类型前缀标记，读回时还原（set 用 \u0001 分隔，防与字符串混淆） */
    private fun encodeValue(value: Any): String = when (value) {
        is Boolean -> "b:$value"
        is Int -> "i:$value"
        is Long -> "l:$value"
        is Float -> "f:$value"
        is Set<*> -> "s:" + value.joinToString(SEPARATOR_SET) { it.toString() }
        else -> "t:$value"
    }

    private fun decodeValue(raw: String): Any = when {
        raw.startsWith("b:") -> raw.substring(2).toBooleanStrictOrNull() ?: raw
        raw.startsWith("i:") -> raw.substring(2).toIntOrNull() ?: raw
        raw.startsWith("l:") -> raw.substring(2).toLongOrNull() ?: raw
        raw.startsWith("f:") -> raw.substring(2).toFloatOrNull() ?: raw
        raw.startsWith("s:") -> raw.substring(2).split(SEPARATOR_SET).filter { it.isNotEmpty() }.toSet()
        raw.startsWith("t:") -> raw.substring(2)
        else -> raw
    }

    private inner class DiskEditor : SharedPreferences.Editor {

        private val pending = LinkedHashMap<String, Any?>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { pending[key.orEmpty()] = value }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key.orEmpty()] = values }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            apply { pending[key.orEmpty()] = value }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            apply { pending[key.orEmpty()] = value }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            apply { pending[key.orEmpty()] = value }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            apply { pending[key.orEmpty()] = value }

        override fun remove(key: String?): SharedPreferences.Editor =
            apply { pending[key.orEmpty()] = null }

        override fun clear(): SharedPreferences.Editor =
            apply { pending[CLEAR_FLAG] = true }

        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() {
            flush()
        }

        private fun flush() {
            if (pending.remove(CLEAR_FLAG) != null) values.clear()
            for ((key, value) in pending) {
                if (key == CLEAR_FLAG) continue
                if (value == null) values.remove(key) else values[key] = value
            }
            pending.clear()
            persist()
        }
    }

    companion object {

        private const val CLEAR_FLAG = "\u0000clear"
        private const val SEPARATOR_SET = "\u0001"

        /** 持久化目录（宿主启动时注入；未注入时仅内存生效） */
        @Volatile
        var storeDir: File? = null

        /** 每个配置名一份实例 */
        private val instances = ConcurrentHashMap<String, MemSharedPreferences>()

        @JvmStatic
        fun of(name: String): MemSharedPreferences =
            instances.computeIfAbsent(name) { MemSharedPreferences(name) }
    }
}
