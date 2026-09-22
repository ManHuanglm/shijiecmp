package android.os

/**
 * 桌面 stub：android.os.Bundle（内存键值容器，put/get 最小集）。
 */
class Bundle {

    private val values = HashMap<String, Any?>()

    fun putString(key: String?, value: String?) {
        values[key.orEmpty()] = value
    }

    fun getString(key: String?): String? = values[key] as? String

    fun putInt(key: String?, value: Int) {
        values[key.orEmpty()] = value
    }

    fun getInt(key: String?, defValue: Int = 0): Int = values[key] as? Int ?: defValue

    fun putBoolean(key: String?, value: Boolean) {
        values[key.orEmpty()] = value
    }

    fun getBoolean(key: String?, defValue: Boolean = false): Boolean = values[key] as? Boolean ?: defValue

    fun containsKey(key: String?): Boolean = values.containsKey(key)

    fun isEmpty(): Boolean = values.isEmpty()

    fun clear() = values.clear()
}
