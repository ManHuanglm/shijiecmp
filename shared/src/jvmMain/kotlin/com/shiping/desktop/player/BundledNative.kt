package com.shiping.desktop.player

import com.shiping.desktop.util.AppLog
import java.io.File
import java.util.jar.JarFile

/**
 * 捆绑原生资源（jar 内 mpv/、vlc/、mdk/ 目录）提取器。
 *
 * MSI/EXE 安装包中 jpackage 仅打包 jars，compose.application.resources.dir 不存在，
 * 安装版首次运行需把 jar 内捆绑的 mpv/vlc/mdk 解压到数据目录 native/ 下再加载。
 * 应用镜像（app image）场景优先走 resources 目录快路径，本提取只作兜底。
 */
object BundledNative {

    private const val TAG = "BundledNative"

    private const val HOME_DIR_NAME = ".shiping-desktop"
    private const val NATIVE_DIR_NAME = "native"
    private const val MARKER_SUFFIX = ".ok"

    /** 已完成提取的目录缓存（进程内幂等） */
    private val extractedDirs = HashMap<String, File?>()

    /** 确保捆绑目录（如 mpv、mdk）已解压到数据目录，返回可用目录；jar 中无资源时返回 null */
    @Synchronized
    fun ensureDir(name: String): File? {
        if (extractedDirs.containsKey(name)) return extractedDirs[name]

        val dest = nativeDirOf(name)
        val marker = File(dest.parentFile, "$name$MARKER_SUFFIX")
        val result = when {
            marker.isFile -> dest
            else -> extractFromClasspath(name, dest)?.also { marker.writeText("ok") }
        }
        extractedDirs[name] = result
        return result
    }

    private fun nativeDirOf(name: String): File =
        File(File(File(System.getProperty("user.home")), HOME_DIR_NAME), "$NATIVE_DIR_NAME/$name")

    /** 扫描自身 jar 中 <name>/ 前缀条目并解压；无条目返回 null */
    private fun extractFromClasspath(name: String, dest: File): File? {
        val jarFile = BundledNative::class.java.protectionDomain.codeSource?.location
            ?.let { location -> runCatching { File(location.toURI()) }.getOrNull() }
            ?: return null
        if (!jarFile.isFile) return null

        val prefix = "$name/"
        var found = false
        runCatching {
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) }
                    .forEach { entry ->
                        found = true
                        val out = File(dest, entry.name.removePrefix(prefix))
                        out.parentFile?.mkdirs()
                        jar.getInputStream(entry).use { input -> out.outputStream().use(input::copyTo) }
                    }
            }
        }.onFailure {
            AppLog.e(TAG, "$name 提取失败: ${it.message}")
            return null
        }
        if (!found) return null
        AppLog.i(TAG, "$name 已从 ${jarFile.name} 提取到 ${dest.absolutePath}")
        return dest
    }
}
