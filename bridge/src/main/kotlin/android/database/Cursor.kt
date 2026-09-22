package android.database

/**
 * 桌面 stub：android.database.Cursor 最小面（按 payload 爬虫实际调用）：
 * moveToFirst / getInt / getString / close。
 */
interface Cursor {

    fun moveToFirst(): Boolean

    fun moveToNext(): Boolean

    fun getInt(columnIndex: Int): Int

    fun getString(columnIndex: Int): String?

    fun close()

    fun isClosed(): Boolean
}
