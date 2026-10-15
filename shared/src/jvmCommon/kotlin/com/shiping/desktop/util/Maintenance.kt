package com.shiping.desktop.util

import java.io.File

/**
 * 维护清理：设置页「清理数据」各项与启动期重置执行（android/jvm 共享，jvmCommon 源集）。
 * 目录语义（对齐 FongMi 清理菜单）：
 * - 网站缓存(验证数据) = files/（网盘 cookie、爬虫 jar 的登录/状态文件）
 * - 扩展存储数据 = cache/（jar 转换产物/guard 解包/python 脚本）+ spider/（jar 下载缓存）
 * - 重置 = 上述全部 + databases/shared_prefs/prefs.properties（保留 logs），经 reset.flag
 *   标记在**下次启动时**执行（运行中 SQLite/WAL 句柄会锁文件，进程内直接删不可靠）。
 */
object Maintenance {

    const val RESET_FLAG = "reset.flag"

    /** 目录大小（字节，不存在返回 0） */
    fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun deleteChildren(dir: File): Boolean {
        if (!dir.isDirectory) return false
        var allOk = true
        dir.listFiles()?.forEach { child ->
            val ok = child.deleteRecursively()
            if (!ok) allOk = false
        }
        return allOk
    }

    /** 清理网站缓存（验证数据）：files/ 下网盘 cookie 与爬虫 jar 状态文件，需重启生效 */
    fun cleanSiteAuth(dataDir: File): Boolean = deleteChildren(File(dataDir, "files"))

    /** 清理扩展存储数据：jar 下载缓存 + 转换/guard/python 等运行时缓存，需重启生效 */
    fun cleanSpiderCache(dataDir: File): Boolean {
        val ok1 = deleteChildren(File(dataDir, "cache"))
        val ok2 = deleteChildren(File(dataDir, "spider"))
        return ok1 && ok2
    }

    /** 标记重置：下次启动执行全量清理（保留 logs 与 reset.flag 本身） */
    fun markReset(dataDir: File): Boolean =
        runCatching { File(dataDir, RESET_FLAG).writeText("reset") }.isSuccess

    /** 启动期执行挂起的重置：清 databases/cache/files/shared_prefs/spider/probe_prefs 与 prefs.properties */
    fun performPendingReset(dataDir: File): Boolean {
        val flag = File(dataDir, RESET_FLAG)
        if (!flag.exists()) return false
        listOf("databases", "cache", "files", "shared_prefs", "spider", "probe_prefs")
            .forEach { deleteChildren(File(dataDir, it)) }
        File(dataDir, "prefs.properties").delete()
        File(dataDir, "shiping.db-shm").delete()
        File(dataDir, "shiping.db-wal").delete()
        val dbDeleted = File(dataDir, "shiping.db").delete()
        flag.delete()
        return true
    }
}
