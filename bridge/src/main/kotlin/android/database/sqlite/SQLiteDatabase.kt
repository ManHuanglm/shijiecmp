package android.database.sqlite

import android.content.ContentValues
import android.database.Cursor
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * 桌面 stub：android.database.sqlite.SQLiteDatabase，基于 xerial sqlite-jdbc 实现。
 * 覆盖爬虫桩实际使用的最小面：openDatabase / execSQL / rawQuery / insert / close。
 * SQLiteClosable.close() 只置标志，释放连接在 close() 内完成。
 */
class SQLiteDatabase private constructor(private val connection: Connection) : SQLiteClosable() {

    fun execSQL(sql: String) {
        connection.createStatement().use { statement ->
            // xerial 一次只执行一条语句，截掉尾部分号兼容多语句写法
            statement.execute(sql.trimEnd(';', ' ', '\n', '\r'))
        }
    }

    fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        connection.prepareStatement(sql.trimEnd(';', ' ', '\n', '\r')).use { statement ->
            bindArgs.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.execute()
        }
    }

    fun rawQuery(sql: String, selectionArgs: Array<out String?>?): Cursor =
        JdbcCursor(
            connection.prepareStatement(sql).also { statement ->
                selectionArgs?.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            }.executeQuery(),
        )

    fun insert(table: String, nullColumnHack: String?, values: ContentValues?): Long {
        val columns = values?.keySet().orEmpty()
        require(columns.isNotEmpty()) { "insert 需要非空 ContentValues" }
        val placeholders = columns.joinToString(",") { "?" }
        val sql = "INSERT INTO $table(${columns.joinToString(",")}) VALUES($placeholders)"
        connection.prepareStatement(sql).use { statement ->
            columns.forEachIndexed { index, column -> statement.setObject(index + 1, values?.get(column)) }
            statement.executeUpdate()
        }
        return 0L
    }

    override fun close() {
        super.close()
        runCatching { connection.close() }
    }

    /** JDBC ResultSet -> android Cursor 适配 */
    private class JdbcCursor(private val resultSet: ResultSet) : Cursor {

        @Volatile
        private var closed = false

        private var rowValid = false

        override fun moveToFirst(): Boolean {
            check(!closed) { "Cursor 已关闭" }
            rowValid = resultSet.next()
            return rowValid
        }

        override fun moveToNext(): Boolean {
            check(!closed) { "Cursor 已关闭" }
            rowValid = resultSet.next()
            return rowValid
        }

        override fun getInt(columnIndex: Int): Int =
            if (rowValid) resultSet.getInt(columnIndex + 1) else 0

        override fun getString(columnIndex: Int): String? =
            if (rowValid) resultSet.getString(columnIndex + 1) else null

        override fun close() {
            closed = true
            runCatching { resultSet.close() }
        }

        override fun isClosed(): Boolean = closed
    }

    companion object {

        const val OPEN_READONLY = 0
        const val OPEN_READWRITE = 1
        const val CREATE_IF_NECESSARY = 268435456

        /**
         * payload 生态依赖的缓存表（列面来自反汇编：merge/G 对 Config 的
         * UPDATE time / SELECT COUNT WHERE url,type / INSERT url,type,time）。
         * 建表 SQL 在 payload 里是运行时解密字符串，静态扫描不可见，
         * Android 端由壳初始化逻辑兜底创建，桌面 stub 必须主动补建，
         * 否则 YCyz 等站点的后台缓存线程直接抛 no such table。
         */
        private val PROVISIONED_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS Config(url TEXT, time INTEGER, type INTEGER)",
        )

        /** 对齐安卓语义：打开（不存在则创建）指定路径的 sqlite 数据库 */
        @JvmStatic
        fun openDatabase(path: String, factory: CursorFactory?, flags: Int): SQLiteDatabase {
            val file = java.io.File(path)
            file.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:$path")
            return SQLiteDatabase(connection).also { it.provisionTables() }
        }
    }

    /** 补建生态依赖表：payload 自身从不执行建表语句 */
    private fun provisionTables() {
        for (ddl in PROVISIONED_TABLES) {
            runCatching {
                connection.createStatement().use { statement -> statement.execute(ddl) }
            }
        }
    }

    /** CursorFactory 占位（安卓指纹签名兼容，桩内不使用） */
    class CursorFactory
}
