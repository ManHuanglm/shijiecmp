package com.shiping.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shiping.shared.prefs.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val Context.prefsDataStore: androidx.datastore.core.DataStore<Preferences> by
    preferencesDataStore(name = "shiping_prefs")

/**
 * KeyValueStore 的 DataStore 实现（统一 UI 通用键值契约）：
 * - 同步读契约经内存缓存满足：构造时阻塞首读一次（应用启动仅此一次），
 *   之后 collect 持续同步；写先更新缓存再异步落盘
 * - 全部值以字符串存储（与桌面 Properties 语义一致），数值型读取时转换
 * - 键名与桌面 DesktopPrefs 对齐（currentApiId/theme_color 等），统一 UI 无感切换
 */
class DataStoreKeyValueStore(
    context: Context,
    private val scope: CoroutineScope,
) : KeyValueStore {

    private val store = context.prefsDataStore

    /** 内存缓存：键 -> 原始字符串值（null 键不存在） */
    private val cache = ConcurrentHashMap<String, String>()

    init {
        // 首读阻塞初始化缓存（DataStore 首次读取即完成文件加载）
        val initial = kotlinx.coroutines.runBlocking { store.data.first() }
        initial.asMap().forEach { (key, value) -> cache[key.name] = value?.toString().orEmpty() }
        scope.launch {
            store.data.collect { prefs ->
                val latest = prefs.asMap().mapKeys { it.key.name }
                cache.keys.removeAll { it !in latest }
                latest.forEach { (key, value) -> cache[key] = value?.toString().orEmpty() }
            }
        }
    }

    override fun getString(key: String, def: String): String = cache[key] ?: def

    override fun getInt(key: String, def: Int): Int = cache[key]?.toIntOrNull() ?: def

    override fun getLong(key: String, def: Long): Long = cache[key]?.toLongOrNull() ?: def

    override fun getFloat(key: String, def: Float): Float = cache[key]?.toFloatOrNull() ?: def

    override fun getBoolean(key: String, def: Boolean): Boolean =
        cache[key]?.toBooleanStrictOrNull() ?: def

    override fun put(key: String, value: Any?) {
        if (value == null) {
            cache.remove(key)
        } else {
            cache[key] = value.toString()
        }
        scope.launch {
            store.edit { prefs ->
                val prefKey = stringPreferencesKey(key)
                if (value == null) prefs.remove(prefKey) else prefs[prefKey] = value.toString()
            }
        }
    }
}
