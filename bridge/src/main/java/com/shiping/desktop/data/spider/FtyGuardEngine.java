package com.shiping.desktop.data.spider;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.VarArg;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.memory.Memory;
import com.shiping.desktop.util.AppLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * 饭太硬加固壳桥接引擎：常驻 unidbg 模拟器（ARM64）执行壳 so 的 DexNative native 方法，
 * 为 payload 爬虫提供字符串解密能力。壳内部流程是在 native getSpider 时执行
 * InitOrigin.setClass(DexNative.class) 注入解密器；桌面 JVM 无法运行 native 壳逻辑，
 * 由 DesktopSpiderRuntime 加载双 jar 后手动执行 InitOrigin.setClass(FtyGuardEngine.class)
 * 完成等价注入，payload 混淆字符串解密经反射落到本类的 static decrypt(String)。
 * JNI 回调分 V（VaList）/非 V（VarArg）两族，均委托到同一套 *Impl 分发。
 */
public final class FtyGuardEngine extends AbstractJni {

    private static final String TAG = "FtyGuard";

    /** 客户端内模拟的应用缓存目录（guest 路径） */
    private static final String GUEST_CACHE = "/data/user/0/com.fty.tv/cache";

    /** 客户端内模拟的应用代码缓存目录（guest 路径） */
    private static final String GUEST_CODE_CACHE = "/data/user/0/com.fty.tv/code_cache";

    /** 客户端内模拟的应用文件目录（guest 路径） */
    private static final String GUEST_FILES = "/data/user/0/com.fty.tv/files";

    /** 客户端内模拟的壳 jar 路径（guest 路径，rootfs 下有真实文件对应） */
    private static final String GUEST_JAR = "/data/app/fty/base.jar";

    /** 模拟的应用包名（壳 so 校验用） */
    private static final String GUEST_PACKAGE = "com.fty.tv";

    /** 壳 native 库条目名（壳 jar 内 lib 下 ftyguard_v8.so） */
    private static final String GUARD_SO_NAME = "ftyguard_v8.so";

    /** guard 资源条目名（壳 jar 内 assets/ftyshinidie.guard，含 payload dex 的 ZIP 容器） */
    private static final String GUARD_ASSET_NAME = "ftyshinidie.guard";

    private static volatile AndroidEmulator emulator;
    private static volatile VM vm;
    private static volatile DvmClass dexNative;
    private static File rootDir;
    private static byte[] soBytes;
    private static byte[] guardBytes;

    /** 当前模拟器绑定的壳 jar 标识（路径+长度+修改时间），换 jar 自动重建 */
    private static String boundJarKey;

    /**
     * 壳生态加载器兜底：Android 上 native proxyInvoke 的 loader 参数来自壳 DexClassLoader，
     * 桌面端壳 Init.loader() 字段无人填充，由 DesktopSpiderRuntime 注入 guard 双 jar 加载器，
     * native 经其 loadClass 加载 payload 站点类（如 Bili）处理代理请求。
     */
    private static volatile ClassLoader fallbackClassLoader;

    /** JNI 回调里创建的 FileOutputStream -> 捕获缓冲（壳 native 经 Java 层写解密 dex） */
    private static final Map<DvmObject<?>, ByteArrayOutputStream> streams = new IdentityHashMap<>();

    /** 已关闭流的缓冲归档（native 写完解密产物即 close，捕获点在 close 桩） */
    private static final List<byte[]> closedDumps = new ArrayList<>();

    private FtyGuardEngine() {
        // 仅静态使用
    }

    /**
     * 初始化常驻模拟器（全局单例，重复调用短路）：从壳 dex jar 提取 so/guard 资产，
     * 构建 guest rootfs 目录结构，加载 so 并完成 JNI_OnLoad 动态注册。
     *
     * @param shellJar 壳 dex jar 本地路径（含 ftyguard_v8.so 与 *.guard 资产）
     * @param workDir  引擎工作目录（资产落盘与 rootfs 根）
     */
    public static synchronized void init(File shellJar, File workDir) throws Exception {
        String jarKey = jarKeyOf(shellJar);
        if (emulator != null && jarKey.equals(boundJarKey)) return;
        if (emulator != null) {
            // 换绑新壳 jar：释放旧模拟器（其 so/guard 状态作废）
            try {
                emulator.close();
            } catch (Exception ignored) {
            }
            emulator = null;
            vm = null;
            dexNative = null;
        }
        long begin = System.currentTimeMillis();
        workDir.mkdirs();
        extractAssets(shellJar, workDir);
        if (soBytes == null) throw new IllegalStateException("壳 jar 内未找到 " + GUARD_SO_NAME);
        if (guardBytes == null) throw new IllegalStateException("壳 jar 内未找到 " + GUARD_ASSET_NAME);

        rootDir = new File(workDir, "rootfs");
        Files.createDirectories(new File(rootDir, GUEST_CACHE.substring(1)).toPath());
        Files.createDirectories(new File(rootDir, GUEST_CODE_CACHE.substring(1)).toPath());
        Files.createDirectories(new File(rootDir, GUEST_FILES.substring(1)).toPath());
        File hostJar = new File(rootDir, GUEST_JAR.substring(1));
        Files.createDirectories(hostJar.getParentFile().toPath());
        Files.copy(shellJar.toPath(), hostJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // guard 副本预放到壳 so 可能查找的各位置（cache/code_cache/files/jar 同级/native 库目录），
        // 不同族壳的查找路径不同，缺失会导致 native 静默跳过解密
        String[] guardDirs = {
            GUEST_CACHE, GUEST_CODE_CACHE, GUEST_FILES,
            GUEST_JAR.substring(0, GUEST_JAR.lastIndexOf('/')),
            "/data/app/fty/lib",
        };
        for (String dir : guardDirs) {
            File hostDir = new File(rootDir, dir.substring(1));
            Files.createDirectories(hostDir.toPath());
            Files.write(new File(hostDir, GUARD_ASSET_NAME).toPath(), guardBytes);
        }
        // so 落盘：同尺寸跳过重写——unidbg 会内存映射该文件，重写已被映射的文件会抛
        // FileSystemException（用户映射区域），多实例并发时也能避免写竞争
        File soFile = new File(workDir, GUARD_SO_NAME);
        if (!soFile.exists() || soFile.length() != soBytes.length) {
            Files.write(soFile.toPath(), soBytes);
        }

        AndroidEmulator instance = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("ftyguard")
                .setRootDir(rootDir)
                .build();
        Memory memory = instance.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));
        vm = instance.createDalvikVM();
        vm.setVerbose(false);
        vm.setJni(new FtyGuardEngine());
        DalvikModule dm = vm.loadLibrary(new File(workDir, GUARD_SO_NAME), false);
        dm.callJNI_OnLoad(instance);
        dexNative = vm.resolveClass("com/github/catvod/spider/DexNative");
        emulator = instance;
        boundJarKey = jarKey;
        AppLog.INSTANCE.i(TAG, "unidbg 引擎就绪（" + (System.currentTimeMillis() - begin) + "ms）");
    }

    /**
     * 执行壳 native 的 DexNative.getLoader 触发壳解密流程，导出解密后的 payload 产物。
     * 部分同族壳（如立播 jar）guard 容器的本地条目头完好但 deflate 数据被加密，
     * 直接解包失败，只能由壳 so 自身完成解密：native 经 Java 层 FileOutputStream 写出
     * 解密产物（JNI 桩捕获缓冲），或经 unidbg rootfs 落盘真实文件（扫描 dex 魔数）。
     * 返回的产物多为 ZIP 容器（含 classes*.dex）或裸 dex 字节，由调用方甄别转换。
     */
    public static synchronized List<byte[]> dumpPayloadDex(File shellJar, File workDir) throws Exception {
        init(shellJar, workDir);
        requireReady();
        try {
            DvmObject<?> app = vm.resolveClass("android/app/Application").newObject("fty-app");
            DvmObject<?> loader = dexNative.callStaticJniMethodObject(
                    emulator, "getLoader(Ljava/lang/Object;)Ljava/lang/Object;", app);
            AppLog.INSTANCE.i(TAG, "getLoader => " + (loader == null ? "null" : String.valueOf(loader.getValue())));
        } catch (Throwable t) {
            // native 收尾阶段可能因桌面桩能力不足报错，此时解密产物通常已写出，不视为失败
            AppLog.INSTANCE.w(TAG, "getLoader 收尾异常（产物可能已产出）: " + t);
        }
        List<byte[]> dumps = new ArrayList<>();
        synchronized (closedDumps) {
            dumps.addAll(closedDumps);
            closedDumps.clear();
        }
        synchronized (streams) {
            for (ByteArrayOutputStream buffer : streams.values()) {
                if (buffer.size() > 0) dumps.add(buffer.toByteArray());
            }
            streams.clear();
        }
        scanDexFiles(rootDir, dumps, 0);
        AppLog.INSTANCE.i(TAG, "native 解密导出产物 " + dumps.size() + " 个");
        return dumps;
    }

    /** 递归扫描 rootfs 中 dex 魔数文件（native 经 unidbg vfs 直接落盘的产物） */
    private static void scanDexFiles(File dir, List<byte[]> out, int depth) {
        if (dir == null || depth > 8) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDexFiles(file, out, depth + 1);
                continue;
            }
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] head = new byte[4];
                if (in.read(head) != 4) continue;
                if (head[0] == 'd' && head[1] == 'e' && head[2] == 'x' && head[3] == '\n') {
                    out.add(Files.readAllBytes(file.toPath()));
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 壳 jar 绑定标识：路径+长度+修改时间（内容更新自动触发模拟器重建） */
    private static String jarKeyOf(File jar) {
        return jar.getAbsolutePath() + "|" + jar.length() + "|" + jar.lastModified();
    }

    /** 执行壳 so 的 DexNative.decrypt（native AES 解密，payload 混淆字符串依赖） */
    public static synchronized String decrypt(String text) {
        requireReady();
        DvmObject<?> result = dexNative.callStaticJniMethodObject(
                emulator, "decrypt(Ljava/lang/String;)Ljava/lang/String;", text);
        return result == null ? null : String.valueOf(result.getValue());
    }

    /** 执行壳 so 的 DexNative.encrypt（部分爬虫回传加密参数用） */
    public static synchronized String encrypt(String text) {
        requireReady();
        DvmObject<?> result = dexNative.callStaticJniMethodObject(
                emulator, "encrypt(Ljava/lang/String;)Ljava/lang/String;", text);
        return result == null ? null : String.valueOf(result.getValue());
    }

    /** 执行壳 so 的 DexNative.noxSign（个别 payload 站点签名用） */
    public static synchronized String noxSign(String a, String b, String c) {
        requireReady();
        DvmObject<?> result = dexNative.callStaticJniMethodObject(
                emulator, "noxSign(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                a, b, c);
        return result == null ? null : String.valueOf(result.getValue());
    }

    /**
     * 执行壳 so 的 DexNative.proxyInvoke（壳生态本地代理分发总入口：B 站 mpd 组装、
     * cookie/弹幕辅助等）。payload 版 DexNative 缺此方法（壳版为 native），运行时经
     * PayloadPatch 注入的桥方法调用至此，由 unidbg 模拟壳 so 的 native 实现。
     *
     * @param loader 壳生态加载器上下文（Android 上为壳 DexClassLoader，桌面传 guard URLClassLoader）
     * @param params 本地代理请求参数（do/url/aid/cid/...）
     * @return 生态约定结构 Object[]{code, mimeType, InputStream, headers?}，无处理返回 null
     */
    public static synchronized Object[] proxyInvoke(Object loader, Object params) {
        requireReady();
        Object effectiveLoader = loader != null ? loader : fallbackClassLoader;
        DvmObject<?> result = dexNative.callStaticJniMethodObject(
                emulator, "proxyInvoke(Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;",
                effectiveLoader == null ? null : wrap(effectiveLoader),
                params == null ? null : wrap(params));
        return unwrapArray(result);
    }

    /** 注入壳生态加载器兜底（guard 双 jar 加载器，native loadClass 用） */
    public static void setFallbackClassLoader(ClassLoader loader) {
        fallbackClassLoader = loader;
    }

    /** Java 对象包装为 unidbg 参数：DvmObject 透传，ClassLoader/Map 挂到壳生态常用 DvmClass */
    private static DvmObject<?> wrap(Object value) {
        if (value instanceof DvmObject<?>) return (DvmObject<?>) value;
        if (value instanceof String) return new StringObject(vm, (String) value);
        if (value instanceof ClassLoader) {
            return vm.resolveClass("dalvik/system/DexClassLoader").newObject(value);
        }
        if (value instanceof Map) return vm.resolveClass("java/util/Map").newObject(value);
        return vm.resolveClass(value.getClass().getName().replace('.', '/')).newObject(value);
    }

    /** unidbg 返回对象解包为 Java 数组：DvmObject 取内值，供 payload 调用方按生态约定消费 */
    private static Object[] unwrapArray(DvmObject<?> result) {
        if (result == null) return null;
        Object value = result.getValue();
        if (!(value instanceof Object[])) return null;
        Object[] raw = (Object[]) value;
        Object[] out = new Object[raw.length];
        for (int i = 0; i < raw.length; i++) {
            Object item = raw[i];
            out[i] = item instanceof DvmObject<?> ? ((DvmObject<?>) item).getValue() : item;
        }
        return out;
    }

    /** unidbg 参数对象解包为 Java 原生值（Map 回调键比较用） */
    private static Object unwrapValue(DvmObject<?> arg) {
        return arg == null ? null : arg.getValue();
    }

    /** VaList/VarArg 两族统一取参（都有 getObjectArg(int)） */
    private static DvmObject<?> getArg(Object args, int index) {
        if (args instanceof VarArg) return ((VarArg) args).getObjectArg(index);
        if (args instanceof VaList) return ((VaList) args).getObjectArg(index);
        return null;
    }

    private static void requireReady() {
        if (emulator == null || dexNative == null) {
            throw new IllegalStateException("FtyGuardEngine 未初始化，先调用 init(shellJar, workDir)");
        }
    }

    private static void extractAssets(File jar, File workDir) throws Exception {
        try (JarFile jarFile = new JarFile(jar)) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(GUARD_SO_NAME)) {
                    soBytes = jarFile.getInputStream(jarFile.getJarEntry(name)).readAllBytes();
                } else if (name.endsWith(".guard")) {
                    guardBytes = jarFile.getInputStream(jarFile.getJarEntry(name)).readAllBytes();
                }
            }
        }
    }

    /** guest 路径映射到 rootfs 下宿主真实路径 */
    private File hostPath(String guest) {
        return new File(rootDir, guest.replace('\\', '/').replaceFirst("^/", ""));
    }

    private String argString(VarArg args, int index) {
        DvmObject<?> arg = args.getObjectArg(index);
        return arg == null ? null : String.valueOf(arg.getValue());
    }

    // ================= JNI 回调入口（V / 非 V 两族统一委托 *Impl） =================

    @Override
    public DvmObject<?> callStaticObjectMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        return staticObjectMethodImpl(vm, dvmClass, signature, vaList);
    }

    @Override
    public DvmObject<?> callStaticObjectMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        return staticObjectMethodImpl(vm, dvmClass, signature, varArg);
    }

    @Override
    public DvmObject<?> callObjectMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        return objectMethodImpl(vm, dvmObject, signature, vaList);
    }

    @Override
    public DvmObject<?> callObjectMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        return objectMethodImpl(vm, dvmObject, signature, varArg);
    }

    @Override
    public int callIntMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        return intMethodImpl(vm, dvmObject, signature, vaList);
    }

    @Override
    public int callIntMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        return intMethodImpl(vm, dvmObject, signature, varArg);
    }

    @Override
    public long callLongMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        return longMethodImpl(vm, dvmObject, signature);
    }

    @Override
    public long callLongMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        return longMethodImpl(vm, dvmObject, signature);
    }

    @Override
    public boolean callBooleanMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        return booleanMethodImpl(vm, dvmObject, signature, vaList);
    }

    @Override
    public boolean callBooleanMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        return booleanMethodImpl(vm, dvmObject, signature, varArg);
    }

    @Override
    public void callVoidMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        voidMethodImpl(vm, dvmObject, signature, vaList);
    }

    @Override
    public void callVoidMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        voidMethodImpl(vm, dvmObject, signature, varArg);
    }

    @Override
    public DvmObject<?> newObjectV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        return newObjectImpl(vm, dvmClass, signature, vaList);
    }

    @Override
    public DvmObject<?> newObject(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        return newObjectImpl(vm, dvmClass, signature, varArg);
    }

    // ================= 分发实现 =================

    @SuppressWarnings("unchecked")
    private DvmObject<?> staticObjectMethodImpl(VM vm, DvmClass dvmClass, String signature, Object args) {
        switch (signature) {
            case "com/github/catvod/spider/Init->classLoader()Ljava/lang/ClassLoader;":
                return vm.resolveClass("java/lang/ClassLoader").newObject(ClassLoader.getSystemClassLoader());
            case "com/github/catvod/spider/Init->context()Landroid/app/Application;":
                return vm.resolveClass("android/app/Application").newObject("fty-app");
            case "com/github/catvod/spider/ProxyOrigin->proxy(Ljava/util/Map;)[Ljava/lang/Object;":
                // 壳 so 的代理分发把非 B 站分支（m3u8/图片等）转回 Java 层 ProxyOrigin，
                // 桌面端经 fallback loader 直连 payload 真实现
                return invokePayloadProxyOrigin(getArg(args, 0));
            default:
                throw new UnsupportedOperationException(TAG + " unhandled static: " + signature);
        }
    }

    /** 经 fallback loader 反射调用 payload 的 ProxyOrigin.proxy(Map)，结果包装回 unidbg 数组 */
    private DvmObject<?> invokePayloadProxyOrigin(DvmObject<?> paramsObj) {
        ClassLoader loader = fallbackClassLoader;
        if (loader == null) return null;
        try {
            Map<String, String> params = paramsObj == null ? null : (Map<String, String>) paramsObj.getValue();
            Object[] result = (Object[]) loader.loadClass("com.github.catvod.spider.ProxyOrigin")
                    .getMethod("proxy", Map.class).invoke(null, params);
            if (result == null) return null;
            DvmObject<?>[] items = new DvmObject<?>[result.length];
            for (int i = 0; i < result.length; i++) items[i] = result[i] == null ? null : wrap(result[i]);
            return new ArrayObject(items);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            AppLog.INSTANCE.w(TAG, "payload ProxyOrigin 处理失败: "
                    + (cause == null ? e : cause));
            return null;
        } catch (Throwable t) {
            AppLog.INSTANCE.w(TAG, "payload ProxyOrigin 反射失败: " + t);
            return null;
        }
    }

    private DvmObject<?> objectMethodImpl(VM vm, DvmObject<?> dvmObject, String signature, VarArg args) {
        switch (signature) {
            case "android/app/Application->getCacheDir()Ljava/io/File;":
            case "android/content/Context->getCacheDir()Ljava/io/File;":
                return vm.resolveClass("java/io/File").newObject(GUEST_CACHE);
            case "android/app/Application->getCodeCacheDir()Ljava/io/File;":
            case "android/content/Context->getCodeCacheDir()Ljava/io/File;":
                return vm.resolveClass("java/io/File").newObject(GUEST_CODE_CACHE);
            case "android/app/Application->getClassLoader()Ljava/lang/ClassLoader;":
            case "android/content/Context->getClassLoader()Ljava/lang/ClassLoader;":
                return vm.resolveClass("java/lang/ClassLoader").newObject(ClassLoader.getSystemClassLoader());
            case "android/app/Application->getFilesDir()Ljava/io/File;":
            case "android/content/Context->getFilesDir()Ljava/io/File;":
                return vm.resolveClass("java/io/File").newObject(GUEST_FILES);
            case "android/app/Application->getPackageName()Ljava/lang/String;":
            case "android/content/Context->getPackageName()Ljava/lang/String;":
                return new StringObject(vm, GUEST_PACKAGE);
            case "android/app/Application->getApplicationContext()Landroid/content/Context;":
            case "android/content/Context->getApplicationContext()Landroid/content/Context;":
                return dvmObject;
            case "android/app/Application->getApplicationInfo()Landroid/content/pm/ApplicationInfo;":
            case "android/content/Context->getApplicationInfo()Landroid/content/pm/ApplicationInfo;":
                return vm.resolveClass("android/content/pm/ApplicationInfo").newObject("fty-appinfo");
            case "java/io/File->getAbsolutePath()Ljava/lang/String;":
            case "java/io/File->getPath()Ljava/lang/String;":
            case "java/io/File->toString()Ljava/lang/String;":
                return new StringObject(vm, String.valueOf(dvmObject.getValue()));
            case "android/app/Application->getAssets()Landroid/content/res/AssetManager;":
            case "android/content/Context->getAssets()Landroid/content/res/AssetManager;":
                return vm.resolveClass("android/content/res/AssetManager").newObject("fty-assets");
            case "android/content/res/AssetManager->open(Ljava/lang/String;)Ljava/io/InputStream;":
            case "java/lang/ClassLoader->getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;": {
                String name = argString(args, 0);
                byte[] data = name != null && name.endsWith(".so") ? soBytes : guardBytes;
                return vm.resolveClass("java/io/InputStream").newObject(new ByteArrayInputStream(data));
            }
            case "java/lang/ClassLoader->getParent()Ljava/lang/ClassLoader;":
                return dvmObject;
            case "java/util/Map->get(Ljava/lang/Object;)Ljava/lang/Object;": {
                Map<?, ?> map = (Map<?, ?>) dvmObject.getValue();
                Object key = unwrapValue(args.getObjectArg(0));
                Object hit = map.get(key);
                return hit == null ? null : wrap(hit);
            }
            case "java/util/Map->toString()Ljava/lang/String;":
            case "java/lang/Object->toString()Ljava/lang/String;":
                return new StringObject(vm, String.valueOf(dvmObject.getValue()));
            case "dalvik/system/DexClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;":
            case "java/lang/ClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;": {
                String name = argString(args, 0);
                ClassLoader loader = (ClassLoader) dvmObject.getValue();
                try {
                    loader.loadClass(name);
                } catch (ClassNotFoundException e) {
                    AppLog.INSTANCE.w(TAG, "native loadClass 失败: " + name);
                }
                return vm.resolveClass(name.replace('.', '/'));
            }
            case "java/lang/Class->getName()Ljava/lang/String;": {
                DvmClass clazz = (DvmClass) dvmObject;
                return new StringObject(vm, clazz.getName().replace('/', '.'));
            }
            default:
                throw new UnsupportedOperationException(TAG + " unhandled method: " + signature);
        }
    }

    private int intMethodImpl(VM vm, DvmObject<?> dvmObject, String signature, VarArg args) {
        switch (signature) {
            case "java/util/Map->size()I":
                return ((Map<?, ?>) dvmObject.getValue()).size();
            case "java/io/InputStream->read([B)I": {
                InputStream in = (InputStream) dvmObject.getValue();
                ByteArray buf = args.getObjectArg(0);
                try {
                    return in.read(buf.getValue());
                } catch (Exception e) {
                    return -1;
                }
            }
            case "java/io/InputStream->read([BII)I": {
                InputStream in = (InputStream) dvmObject.getValue();
                ByteArray buf = args.getObjectArg(0);
                try {
                    return in.read(buf.getValue(), args.getIntArg(1), args.getIntArg(2));
                } catch (Exception e) {
                    return -1;
                }
            }
            case "java/io/InputStream->available()I": {
                InputStream in = (InputStream) dvmObject.getValue();
                try {
                    return in.available();
                } catch (Exception e) {
                    return 0;
                }
            }
            default:
                throw new UnsupportedOperationException(TAG + " unhandled int: " + signature);
        }
    }

    private long longMethodImpl(VM vm, DvmObject<?> dvmObject, String signature) {
        if ("java/io/File->length()J".equals(signature)) {
            File host = hostPath(String.valueOf(dvmObject.getValue()));
            return host.isFile() ? host.length() : 0L;
        }
        throw new UnsupportedOperationException(TAG + " unhandled long: " + signature);
    }

    private boolean booleanMethodImpl(VM vm, DvmObject<?> dvmObject, String signature, Object args) {
        switch (signature) {
            case "java/util/Map->containsKey(Ljava/lang/Object;)Z": {
                Map<?, ?> map = (Map<?, ?>) dvmObject.getValue();
                return map.containsKey(unwrapValue(getArg(args, 0)));
            }
            case "java/util/Map->isEmpty()Z":
                return ((Map<?, ?>) dvmObject.getValue()).isEmpty();
            case "java/io/File->setReadOnly()Z":
                return true;
            case "java/io/File->mkdirs()Z":
            case "java/io/File->mkdir()Z": {
                File host = hostPath(String.valueOf(dvmObject.getValue()));
                return host.isDirectory() || host.mkdirs();
            }
            case "java/io/File->delete()Z": {
                File host = hostPath(String.valueOf(dvmObject.getValue()));
                return !host.exists() || host.delete();
            }
            case "java/io/File->exists()Z": {
                File host = hostPath(String.valueOf(dvmObject.getValue()));
                return host.exists();
            }
            case "java/io/File->isDirectory()Z": {
                File host = hostPath(String.valueOf(dvmObject.getValue()));
                return host.isDirectory();
            }
            case "java/io/InputStream->markSupported()Z":
                return false;
            default:
                throw new UnsupportedOperationException(TAG + " unhandled boolean: " + signature);
        }
    }

    private void voidMethodImpl(VM vm, DvmObject<?> dvmObject, String signature, VarArg args) {
        switch (signature) {
            case "java/io/InputStream->close()V": {
                try {
                    ((InputStream) dvmObject.getValue()).close();
                } catch (Exception ignored) {
                }
                return;
            }
            case "java/io/OutputStream->close()V":
            case "java/io/FileOutputStream->close()V": {
                // native 写完解密产物即关闭：close 是最后捕获时机，归档非空缓冲
                ByteArrayOutputStream buffer = streams.remove(dvmObject);
                if (buffer != null && buffer.size() > 0) {
                    synchronized (closedDumps) {
                        closedDumps.add(buffer.toByteArray());
                    }
                }
                return;
            }
            case "java/io/OutputStream->write([B)V":
            case "java/io/FileOutputStream->write([B)V": {
                ByteArray data = args.getObjectArg(0);
                streams.computeIfAbsent(dvmObject, k -> new ByteArrayOutputStream())
                        .write(data.getValue(), 0, data.getValue().length);
                return;
            }
            case "java/io/OutputStream->write([BII)V":
            case "java/io/FileOutputStream->write([BII)V": {
                ByteArray data = args.getObjectArg(0);
                streams.computeIfAbsent(dvmObject, k -> new ByteArrayOutputStream())
                        .write(data.getValue(), args.getIntArg(1), args.getIntArg(2));
                return;
            }
            default:
                throw new UnsupportedOperationException(TAG + " unhandled void: " + signature);
        }
    }

    private DvmObject<?> newObjectImpl(VM vm, DvmClass dvmClass, String signature, VarArg args) {
        switch (signature) {
            case "dalvik/system/DexClassLoader-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V":
                return dvmClass.newObject(argString(args, 0));
            case "java/io/File-><init>(Ljava/lang/String;)V":
                return dvmClass.newObject(argString(args, 0));
            case "java/io/File-><init>(Ljava/io/File;Ljava/lang/String;)V": {
                DvmObject<?> parent = args.getObjectArg(0);
                return dvmClass.newObject(parent.getValue() + "/" + argString(args, 1));
            }
            case "java/io/FileInputStream-><init>(Ljava/lang/String;)V": {
                File host = hostPath(argString(args, 0));
                byte[] data = new byte[0];
                if (host.isFile()) {
                    try {
                        data = Files.readAllBytes(host.toPath());
                    } catch (Exception e) {
                        AppLog.INSTANCE.w(TAG, "读宿主文件失败: " + e);
                    }
                }
                return dvmClass.newObject(new ByteArrayInputStream(data));
            }
            case "java/io/FileOutputStream-><init>(Ljava/io/File;)V":
            case "java/io/FileOutputStream-><init>(Ljava/lang/String;)V": {
                // 挂到 java/io/OutputStream 类，规避 DvmClass.getMethod 父类查找限制
                DvmObject<?> out = vm.resolveClass("java/io/OutputStream").newObject(argString(args, 0));
                streams.put(out, new ByteArrayOutputStream());
                return out;
            }
            default:
                throw new UnsupportedOperationException(TAG + " unhandled new: " + signature);
        }
    }

    // ================= 字段 =================

    @Override
    public DvmObject<?> getStaticObjectField(BaseVM vm, DvmClass dvmClass, String signature) {
        if (signature.startsWith("java/io/File->separator")) return new StringObject(vm, "/");
        if (signature.startsWith("java/io/File->pathSeparator")) return new StringObject(vm, ":");
        if (signature.startsWith("android/os/Build->CPU_ABI")) return new StringObject(vm, "arm64-v8a");
        return super.getStaticObjectField(vm, dvmClass, signature);
    }

    @Override
    public int getStaticIntField(BaseVM vm, DvmClass dvmClass, String signature) {
        if (signature.startsWith("android/os/Build$VERSION->SDK_INT")) return 30;
        return super.getStaticIntField(vm, dvmClass, signature);
    }

    @Override
    public DvmObject<?> getObjectField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        if (signature.startsWith("android/content/pm/ApplicationInfo->sourceDir")) {
            return new StringObject(vm, GUEST_JAR);
        }
        if (signature.startsWith("android/content/pm/ApplicationInfo->nativeLibraryDir")) {
            return new StringObject(vm, "/data/app/fty/lib");
        }
        if (signature.startsWith("android/content/pm/ApplicationInfo->dataDir")) {
            return new StringObject(vm, "/data/user/0/com.fty.tv");
        }
        return super.getObjectField(vm, dvmObject, signature);
    }
}
