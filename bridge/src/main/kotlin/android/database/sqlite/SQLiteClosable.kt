package android.database.sqlite

/**
 * 桌面 stub：android.database.sqlite.SQLiteClosable 最小面（关闭状态跟踪）。
 */
abstract class SQLiteClosable {

    @Volatile
    private var closed = false

    open fun close() {
        closed = true
    }

    fun isClosed(): Boolean = closed
}
