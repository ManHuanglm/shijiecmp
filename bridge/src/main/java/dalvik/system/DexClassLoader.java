package dalvik.system;

/**
 * 桌面 stub：DexClassLoader 空壳（仅满足 quickjs JS Spider 的构造签名，桌面无 dex 动态加载；
 * Android 运行时由真实类按同 FQN 覆盖）。
 */
public class DexClassLoader extends ClassLoader {

    public DexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(parent);
    }

    public Class<?> loadClass(String name) throws ClassNotFoundException {
        throw new ClassNotFoundException("DexClassLoader stub");
    }
}
