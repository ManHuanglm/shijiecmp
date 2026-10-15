package com.shiping.desktop.data.spider;

import com.shiping.desktop.util.AppLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * payload 产物字节码补丁：给加固壳 payload 的 DexNative 注入 proxyInvoke 静态桥方法。
 * <p>
 * 背景：壳生态中 DexNative.proxyInvoke(Object,Object) 是本地代理分发总入口（B 站 mpd
 * 组装、cookie/弹幕辅助等），真实现是壳 so 的 native 方法（壳版 DexNative 为 native 声明）。
 * payload 版 DexNative（纯 Java，danmuStart 等实现）缺此方法，而壳内代码运行时会以
 * MethodHandle 查找该签名，缺失即抛 NoSuchMethodError，B 站等代理请求全部静默失败。
 * <p>
 * 修复：向 payload jar 的 DexNative 注入 public static proxyInvoke(Object,Object)，
 * 方法体直接 INVOKESTATIC 到 FtyGuardEngine.proxyInvoke（unidbg 模拟壳 so native 执行）。
 * 幂等：已含同签名方法时原样返回，缓存命中路径可安全重复调用。
 */
public final class PayloadPatch {

    private static final String TAG = "PayloadPatch";

    /** 目标类内部名（payload 内的 DexNative） */
    private static final String DEX_NATIVE_INTERNAL = "com/github/catvod/spider/DexNative";

    /** 注入方法签名（与壳版 native 声明一致） */
    private static final String PROXY_INVOKE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;";

    /** 桥接引擎内部名（宿主 classpath 可见，payload loader parent-first 解析） */
    private static final String ENGINE_INTERNAL = "com/shiping/desktop/data/spider/FtyGuardEngine";

    private PayloadPatch() {
        // 仅静态使用
    }

    /** 给 payload jar 的 DexNative 注入 proxyInvoke 桥方法（幂等，无该类的产物原样跳过） */
    public static synchronized void patchDexNativeProxyInvoke(File payloadJar) {
        try {
            byte[] dexNativeClass = readEntry(payloadJar, DEX_NATIVE_INTERNAL + ".class");
            if (dexNativeClass == null) return;
            if (hasProxyInvoke(dexNativeClass)) {
                AppLog.INSTANCE.i(TAG, "DexNative 已含 proxyInvoke，跳过补丁: " + payloadJar.getName());
                return;
            }
            byte[] patched = injectProxyInvoke(dexNativeClass);
            rewriteJar(payloadJar, DEX_NATIVE_INTERNAL + ".class", patched);
            AppLog.INSTANCE.i(TAG, "DexNative 注入 proxyInvoke 桥完成: " + payloadJar.getName()
                    + "（" + payloadJar.length() + " 字节）");
        } catch (Throwable t) {
            // 补丁失败不阻断源加载：仅 B 站等壳代理功能受影响，与既有行为一致
            AppLog.INSTANCE.w(TAG, "payload 补丁失败（代理分发可能受限）: " + t);
        }
    }

    /** 读取 jar 内单条目字节，缺失返回 null */
    private static byte[] readEntry(File jar, String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    /** 类中是否已存在 proxyInvoke(Object,Object) 方法 */
    private static boolean hasProxyInvoke(byte[] classBytes) {
        boolean[] found = {false};
        new ClassReader(classBytes).accept(new ClassVisitorCheck(found), 0);
        return found[0];
    }

    /** 注入 proxyInvoke 桥方法：参数原样透传到 FtyGuardEngine.proxyInvoke */
    private static byte[] injectProxyInvoke(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        // COMPUTE_MAXS 重算操作数栈；不使用 COMPUTE_FRAMES 以避免触发额外的类加载
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(writer, 0);
        MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "proxyInvoke", PROXY_INVOKE_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ENGINE_INTERNAL, "proxyInvoke", PROXY_INVOKE_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        return writer.toByteArray();
    }

    /** 重写 jar：逐条目复制，替换目标类字节（唯一临时文件 + 原子替换，多实例并发安全） */
    private static void rewriteJar(File jar, String entryName, byte[] newBytes) throws Exception {
        File temp = new File(jar.getParentFile(), jar.getName() + "." + System.nanoTime() + ".patched");
        try (ZipFile source = new ZipFile(jar);
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temp.toPath()))) {
            var entries = source.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals(entryName)) {
                    output.write(newBytes);
                } else {
                    try (InputStream in = source.getInputStream(entry)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) output.write(buffer, 0, read);
                    }
                }
                output.closeEntry();
            }
        }
        Files.move(temp.toPath(), jar.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 仅检测方法的 ClassVisitor */
    private static final class ClassVisitorCheck extends ClassVisitor {
        private final boolean[] found;

        ClassVisitorCheck(boolean[] found) {
            super(Opcodes.ASM9);
            this.found = found;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if ("proxyInvoke".equals(name) && PROXY_INVOKE_DESC.equals(descriptor)) found[0] = true;
            return null;
        }
    }
}
