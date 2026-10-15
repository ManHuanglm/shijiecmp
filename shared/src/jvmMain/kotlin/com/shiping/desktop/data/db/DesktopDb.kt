package com.shiping.desktop.data.db

import com.shiping.desktop.util.AppLog
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * 桌面版 SQLite 数据库（JDBC，单连接串行访问）。
 * 表结构对齐 app 模块 Room 实体，随仓库层执行建表迁移。
 */
class DesktopDb(dbFile: File) {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

    init {
        connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        createTables()
        migrate()
    }

    /** 建表（IF NOT EXISTS 幂等，结构与 app 模块 Room 实体对齐） */
    private fun createTables() = exec(
        """
        CREATE TABLE IF NOT EXISTS api_sources (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            enabled INTEGER NOT NULL DEFAULT 1,
            sortOrder INTEGER NOT NULL DEFAULT 0,
            siteType INTEGER NOT NULL DEFAULT 0,
            jarUrl TEXT NOT NULL DEFAULT '',
            spiderKey TEXT NOT NULL DEFAULT '',
            ext TEXT NOT NULL DEFAULT '',
            locked INTEGER NOT NULL DEFAULT 0,
            playRecord INTEGER NOT NULL DEFAULT 1,
            searchable INTEGER NOT NULL DEFAULT 1,
            userAgent TEXT NOT NULL DEFAULT '',
            createdAt INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS parse_sources (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            enabled INTEGER NOT NULL DEFAULT 1,
            sortOrder INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS tv_sources (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            createdAt INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS play_history (
            vodId INTEGER NOT NULL,
            sourceUrl TEXT NOT NULL,
            title TEXT NOT NULL,
            vodPic TEXT NOT NULL DEFAULT '',
            lastEpisodeName TEXT NOT NULL DEFAULT '',
            lastPositionMs INTEGER NOT NULL DEFAULT 0,
            durationMs INTEGER NOT NULL DEFAULT 0,
            watchedAt INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (vodId, sourceUrl)
        );
        CREATE TABLE IF NOT EXISTS favorites (
            vodId INTEGER NOT NULL,
            sourceUrl TEXT NOT NULL,
            title TEXT NOT NULL,
            vodPic TEXT NOT NULL DEFAULT '',
            addedAt INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (vodId, sourceUrl)
        );
        CREATE TABLE IF NOT EXISTS downloads (
            contentId TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            vodPic TEXT NOT NULL DEFAULT '',
            url TEXT NOT NULL,
            quality TEXT NOT NULL DEFAULT '',
            status INTEGER NOT NULL DEFAULT 0,
            progress INTEGER NOT NULL DEFAULT 0,
            bytesDownloaded INTEGER NOT NULL DEFAULT 0,
            totalBytes INTEGER NOT NULL DEFAULT 0,
            speedBytesPerSecond INTEGER NOT NULL DEFAULT 0,
            errorMessage TEXT,
            headersJson TEXT NOT NULL DEFAULT '',
            createdAt INTEGER NOT NULL DEFAULT 0,
            completedAt INTEGER NOT NULL DEFAULT 0,
            localPath TEXT NOT NULL DEFAULT '',
            completedSegments INTEGER NOT NULL DEFAULT 0
        );
        """.trimIndent(),
    )

    /** 旧库增量迁移（ALTER 重复执行报 duplicate column 时忽略） */
    private fun migrate() {
        listOf(
            "ALTER TABLE api_sources ADD COLUMN locked INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE api_sources ADD COLUMN playRecord INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE api_sources ADD COLUMN searchable INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE api_sources ADD COLUMN userAgent TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE downloads ADD COLUMN completedSegments INTEGER NOT NULL DEFAULT 0",
        ).forEach { sql ->
            runCatching { exec(sql) }
                .onFailure { AppLog.i("DesktopDb", "迁移跳过: ${it.message}") }
        }
    }

    /** 执行写语句（可含多条分号分隔），返回受影响行数合计 */
    fun exec(sql: String, params: List<Any?> = emptyList()): Int {
        synchronized(connection) {
            var affected = 0
            for (statement in sql.split(";")) {
                val trimmed = statement.trim()
                if (trimmed.isEmpty()) continue
                prepare(trimmed, params).use { affected += it.executeUpdate() }
            }
            return affected
        }
    }

    /** 执行写语句并返回自增主键 */
    fun insert(sql: String, params: List<Any?> = emptyList()): Long = synchronized(connection) {
        prepare(sql, params).use { statement ->
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else 0L
            }
        }
    }

    /** 查询并映射结果集 */
    fun <T> query(sql: String, params: List<Any?> = emptyList(), mapper: (ResultSet) -> T): List<T> =
        synchronized(connection) {
            prepare(sql, params).use { statement ->
                statement.executeQuery().use { resultSet ->
                    val result = mutableListOf<T>()
                    while (resultSet.next()) result += mapper(resultSet)
                    result
                }
            }
        }

    private fun prepare(sql: String, params: List<Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        params.forEachIndexed { index, value ->
            when (value) {
                null -> statement.setObject(index + 1, null)
                is Boolean -> statement.setInt(index + 1, if (value) 1 else 0)
                is Int -> statement.setInt(index + 1, value)
                is Long -> statement.setLong(index + 1, value)
                is Float -> statement.setDouble(index + 1, value.toDouble())
                is Double -> statement.setDouble(index + 1, value)
                else -> statement.setString(index + 1, value.toString())
            }
        }
        return statement
    }

    fun close() {
        runCatching { connection.close() }.onFailure { AppLog.w("DesktopDb", "关闭数据库失败: ${it.message}") }
    }
}
