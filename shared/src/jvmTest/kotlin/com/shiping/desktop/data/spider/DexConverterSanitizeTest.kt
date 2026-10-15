package com.shiping.desktop.data.spider

import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_NATIVE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.RETURN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DexConverter 构造器非法修饰清洗验证（南风 Yoursmile jar 的 NetPan 类伪 native 构造器场景） */
class DexConverterSanitizeTest {

    /** 生成模拟 dex2jar 伪修饰产物：<init> 带 PUBLIC|NATIVE 且无方法体 */
    private fun dirtyClassBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(52, ACC_PUBLIC, "com/x/Fake", null, "java/lang/Object", null)
        writer.visitMethod(ACC_PUBLIC or ACC_NATIVE, "<init>", "()V", null, null).visitEnd()
        writer.visitMethod(ACC_PUBLIC, "normal", "()V", null, null).apply {
            visitCode()
            visitInsn(RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** 无方法体的正常类（非构造器合法路径，验证清洗不影响） */
    private fun cleanClassBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(52, ACC_PUBLIC, "com/x/Clean", null, "java/lang/Object", null)
        writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitInsn(RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** 读清洗后 class 的 <init> 访问位与是否有方法体 */
    private fun inspectInit(bytes: ByteArray): Pair<Int, Boolean> {
        var access = -1
        var hasCode = false
        ClassReader(bytes).accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visitMethod(
                methodAccess: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): org.objectweb.asm.MethodVisitor? {
                if (name != "<init>") return null
                access = methodAccess
                return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    override fun visitCode() {
                        hasCode = true
                        super.visitCode()
                    }
                }
            }
        }, 0)
        return access to hasCode
    }

    @Test
    fun 非法构造器清洗清位并补方法体() {
        val cleaned = DexConverter.sanitizeIllegalInit(dirtyClassBytes())
        val (access, hasCode) = inspectInit(cleaned)
        assertEquals(ACC_PUBLIC, access, "NATIVE 位应被清除且保留 PUBLIC")
        assertTrue(hasCode, "无方法体的伪 native 构造器应补 super()+return 空体")
    }

    @Test
    fun 干净类原样返回() {
        val bytes = cleanClassBytes()
        assertTrue(DexConverter.sanitizeIllegalInit(bytes).contentEquals(bytes), "无非法位的类应原样返回")
    }

    @Test
    fun 清洗后字节可被JVM加载实例化() {
        val cleaned = DexConverter.sanitizeIllegalInit(dirtyClassBytes())
        val jar = File.createTempFile("sanitize", ".jar").apply { deleteOnExit() }
        ZipOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("com/x/Fake.class"))
            output.write(cleaned)
            output.closeEntry()
        }
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getSystemClassLoader())
        val constructor = loader.loadClass("com.x.Fake").declaredConstructors[0]
        assertFalse(java.lang.reflect.Modifier.isNative(constructor.modifiers))
        constructor.newInstance()
    }

    @Test
    fun 带合成位构造器清洗仅移除非法位() {
        // ACC_SYNTHETIC(0x1000) 为合法附加位，应保留
        val writer = ClassWriter(0)
        writer.visit(52, ACC_PUBLIC, "com/x/Syn", null, "java/lang/Object", null)
        writer.visitMethod(ACC_PUBLIC or ACC_NATIVE or ACC_SYNTHETIC, "<init>", "()V", null, null).visitEnd()
        writer.visitEnd()
        val (access, hasCode) = inspectInit(DexConverter.sanitizeIllegalInit(writer.toByteArray()))
        assertEquals(ACC_PUBLIC or ACC_SYNTHETIC, access, "仅清除 NATIVE，保留合法合成位")
        assertTrue(hasCode)
    }

    /** 读清洗后 class 的字段 ConstantValue（name -> value；无 attribute 为 null） */
    private fun inspectFieldValues(bytes: ByteArray): Map<String, Any?> {
        val fields = HashMap<String, Any?>()
        ClassReader(bytes).accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): org.objectweb.asm.FieldVisitor? {
                fields[name] = value
                return null
            }
        }, 0)
        return fields
    }

    /** 生成模拟 dex2jar 产物：引用类型字段（HashMap）被错误携带字符串 ConstantValue（Init 类坏点形态） */
    private fun badConstantValueClassBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(52, ACC_PUBLIC, "com/x/BadCv", null, "java/lang/Object", null)
        writer.visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "e", "Ljava/util/HashMap;", null, "").visitEnd()
        writer.visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "v", "Ljava/lang/String;", null, "08.16").visitEnd()
        writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitInsn(RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun 引用类型字段非法字符串初始值被移除() {
        val fields = inspectFieldValues(DexConverter.sanitizeIllegalInit(badConstantValueClassBytes()))
        assertEquals(null, fields["e"], "HashMap 字段的字符串 ConstantValue 应被移除（ClassFormatError 根因），字段回落 null")
    }

    @Test
    fun String与基本类型字段合法初始值保留() {
        val fields = inspectFieldValues(DexConverter.sanitizeIllegalInit(badConstantValueClassBytes()))
        assertEquals("08.16", fields["v"], "String 字段的合法 ConstantValue 应保留")
    }

    /** 生成模拟 dex2jar 产物：Object 赋给 Number 字段（缺 checkcast，VerifyError: putfield） */
    private fun badPutFieldBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(52, ACC_PUBLIC, "com/x/BadPf", null, "java/lang/Object", null)
        writer.visitField(ACC_PUBLIC, "target", "Ljava/lang/Number;", null, null).visitEnd()
        writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitMethod(ACC_PUBLIC, "set", "(Ljava/lang/Object;)V", null, null).apply {
            visitCode()
            visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
            visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, "com/x/BadPf", "target", "Ljava/lang/Number;")
            visitInsn(RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** 生成模拟 dex2jar 产物：ArrayList 从 Number 签名方法返回（缺 checkcast，VerifyError: areturn） */
    private fun badReturnBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(52, ACC_PUBLIC, "com/x/BadRet", null, "java/lang/Object", null)
        writer.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Number;", null, null).apply {
            visitCode()
            visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "java/util/ArrayList")
            visitInsn(org.objectweb.asm.Opcodes.DUP)
            visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
            visitInsn(org.objectweb.asm.Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** 加载并调用目标方法（JVM 校验惰性逐方法，须执行到方法体才触发校验），返回异常（null = 通过） */
    private fun invokeError(bytes: ByteArray, className: String, method: String, args: Array<Any> = emptyArray()): Throwable? = try {
        val jar = File.createTempFile("linkcheck", ".jar").apply { deleteOnExit() }
        ZipOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("$className.class"))
            output.write(bytes)
            output.closeEntry()
        }
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getSystemClassLoader())
        val cls = loader.loadClass(className.replace('/', '.'))
        val instance = cls.getDeclaredConstructor().newInstance()
        cls.getMethod(method, *args.map { it.javaClass }.toTypedArray()).invoke(instance, *args)
        null
    } catch (t: Throwable) {
        t
    }

    /** 异常链内是否含目标类型 */
    private fun Throwable?.hasInChain(type: Class<out Throwable>): Boolean =
        generateSequence(this) { it.cause }.any { type.isInstance(it) }

    @Test
    fun putfield缺checkcast修补后通过JVM校验() {
        val bytes = badPutFieldBytes()
        val before = invokeError(bytes, "com/x/BadPf", "set", arrayOf(Any()))
        assertTrue(before.hasInChain(java.lang.VerifyError::class.java), "修补前 set 方法校验应 VerifyError")
        // 端到端管线：指令修补 + 帧重算（52 版本无 StackMapTable 直接 VerifyError）
        val repaired = DexConverter.recomputeFrames(DexConverter.repairVerify(bytes, emptyMap()), null)
        val after = invokeError(repaired, "com/x/BadPf", "set", arrayOf(Any()))
        assertFalse(after.hasInChain(java.lang.VerifyError::class.java), "修补后不应再 VerifyError，实际: $after")
    }

    @Test
    fun areturn缺checkcast修补后通过JVM校验() {
        val bytes = badReturnBytes()
        val before = invokeError(bytes, "com/x/BadRet", "get")
        assertTrue(before.hasInChain(java.lang.VerifyError::class.java), "修补前 get 方法校验应 VerifyError")
        val repaired = DexConverter.recomputeFrames(DexConverter.repairVerify(bytes, emptyMap()), null)
        val after = invokeError(repaired, "com/x/BadRet", "get")
        assertFalse(after.hasInChain(java.lang.VerifyError::class.java), "修补后不应再 VerifyError，实际: $after")
    }

    /** 生成模拟 dex2jar 产物：new 匿名类（extends Object）后 invokespecial 到错误父类 <init>（XYQHiker 场景） */
    private fun badInitOwnerBytes(): Pair<ByteArray, ByteArray> {
        val inner = ClassWriter(0)
        inner.visit(52, ACC_PUBLIC, "com/x/Outer\$2", null, "java/lang/Object", null)
        inner.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        inner.visitEnd()
        val outer = ClassWriter(0)
        outer.visit(52, ACC_PUBLIC, "com/x/Outer", null, "java/lang/Object", null)
        outer.visitMethod(ACC_PUBLIC, "v", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "com/x/Outer\$2")
            visitInsn(org.objectweb.asm.Opcodes.DUP)
            visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "com/x/xh", "<init>", "()V", false)
            visitInsn(RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        outer.visitEnd()
        return outer.toByteArray() to inner.toByteArray()
    }

    /** 读 class 指定方法的 invokespecial <init> owner */
    private fun inspectInitOwner(bytes: ByteArray, className: String, methodName: String): String? {
        var owner: String? = null
        ClassReader(bytes).accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): org.objectweb.asm.MethodVisitor? {
                if (name != methodName) return null
                return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        methodOwner: String,
                        methodName: String,
                        methodDescriptor: String,
                        isInterface: Boolean,
                    ) {
                        if (opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL && methodName == "<init>") owner = methodOwner
                    }
                }
            }
        }, 0)
        return owner
    }

    @Test
    fun invokespecial错位owner修正为真实父类() {
        val (outer, inner) = badInitOwnerBytes()
        // 错误父类 xh 也需在产物集合（层次解析可载才会执行相容判定）
        val xh = ClassWriter(0)
        xh.visit(52, ACC_PUBLIC, "com/x/xh", null, "java/lang/Object", null)
        xh.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        xh.visitEnd()
        val classBytes = mapOf(
            "com/x/Outer\$2.class" to inner,
            "com/x/xh.class" to xh.toByteArray(),
        )
        val repaired = DexConverter.repairVerify(outer, classBytes)
        assertEquals(
            "java/lang/Object",
            inspectInitOwner(repaired, "com/x/Outer", "v"),
            "invokespecial <init> owner 应改写为 receiver 的真实父类（Outer\$2 extends Object）",
        )
    }
}
