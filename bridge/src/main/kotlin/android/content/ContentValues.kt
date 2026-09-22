package android.content

/**
 * 桌面 stub：android.content.ContentValues 最小面（键值容器，sqlite insert 参数）。
 * 注意包名：Android 平台该类位于 android.content（非 android.database）。
 */
class ContentValues {

    private val map = LinkedHashMap<String, Any?>()

    fun put(key: String?, value: String?) {
        map[key ?: return] = value
    }

    fun put(key: String?, value: Int?) {
        map[key ?: return] = value
    }

    fun put(key: String?, value: Long?) {
        map[key ?: return] = value
    }

    fun put(key: String?, value: Boolean?) {
        map[key ?: return] = value
    }

    fun put(key: String?, value: Float?) {
        map[key ?: return] = value
    }

    fun put(key: String?, value: Double?) {
        map[key ?: return] = value
    }

    fun getAsString(key: String?): String? = map[key] as? String

    fun getAsInteger(key: String?): Int? = (map[key] as? Number)?.toInt()

    fun getAsLong(key: String?): Long? = (map[key] as? Number)?.toLong()

    fun getAsFloat(key: String?): Float? = (map[key] as? Number)?.toFloat()

    /** 原始值读取（供 SQLiteDatabase.insert 绑定参数） */
    fun get(key: String?): Any? = map[key]

    fun keySet(): Set<String> = map.keys

    fun size(): Int = map.size
}
