package dalvik.system

import com.shiping.desktop.data.spider.DexConverter
import java.io.File
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 桌面版 DexClassLoader stub（包名必须为 dalvik.system，与 Android 系统类一致）：
 * 加固壳爬虫 jar（饭太硬等）的 Init 引导经 new DexClassLoader(...) 二次加载解密后的
 * dex，桌面 JVM 无该系统类会 ClassNotFoundException，导致解密流程中断、爬虫桩类
 * 永远拿不到真实实现。实现：dex 文件（或含 dex 的 jar）经 dex2jar 转换后用
 * URLClassLoader 加载，转换产物按 dex 内容 md5 缓存到临时目录。
 */
class DexClassLoader(
    dexPath: String,
    @Suppress("UNUSED_PARAMETER") optimizedDirectory: String?,
    @Suppress("UNUSED_PARAMETER") librarySearchPath: String?,
    parent: ClassLoader,
) : URLClassLoader(toUrls(dexPath), parent) {

    companion object {

        /** dexPath 解析为文件列表：兼容单路径、';'/'\n' 多路径与 Windows 盘符（C:\x.dex 不可按 ':' 拆） */
        private fun toDexFiles(dexPath: String): List<File> {
            val direct = File(dexPath)
            if (direct.isFile) return listOf(direct)
            return dexPath.split('\n', ';')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .flatMap { seg ->
                    val segFile = File(seg)
                    if (segFile.isFile) sequenceOf(segFile)
                    else seg.split(':').asSequence().map(::File).filter { it.isFile }
                }
                .toList()
        }

        private fun toUrls(dexPath: String): Array<java.net.URL> =
            toDexFiles(dexPath)
                .map { file ->
                    val target = if (isDexFile(file)) File(convertDex(file)) else file
                    target.toURI().toURL()
                }
                .toTypedArray()

        /** 判定裸 dex 文件（扩展名或魔数 "dex\n"；jar/zip 原样加载由 DexConverter 兜底处理其内嵌 dex） */
        private fun isDexFile(file: File): Boolean {
            if (file.name.endsWith(".dex")) return true
            return runCatching {
                file.inputStream().use { input ->
                    val head = ByteArray(4)
                    input.read(head) == 4 && head.toString(Charsets.US_ASCII) == "dex\n"
                }
            }.getOrDefault(false)
        }

        /**
         * 裸 dex 转 jar：包装为含 classes.dex 条目的 zip（DexConverter 按 zip 条目扫描），
         * 转换产物按 dex 内容 md5 缓存——壳重复解密可能写同一路径，路径 md5 会误命中旧内容。
         */
        private fun convertDex(dex: File): String {
            val cacheDir = File(System.getProperty("java.io.tmpdir"), "shiping-dex-cache").apply { mkdirs() }
            val contentMd5 = fileMd5(dex)
            val outJar = File(cacheDir, "$contentMd5.jar")
            if (outJar.isFile && outJar.length() > 0) return outJar.absolutePath

            val wrapper = File(cacheDir, "$contentMd5.wrap.jar")
            ZipOutputStream(FileOutputStream(wrapper)).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                dex.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            runCatching {
                DexConverter.convert(wrapper, outJar)
            }.also { wrapper.delete() }.getOrThrow()
            return outJar.absolutePath
        }

        private fun fileMd5(file: File): String = runCatching {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }
}
