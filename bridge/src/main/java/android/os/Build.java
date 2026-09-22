package android.os;

/**
 * 桌面 stub：android.os.Build（爬虫构造 UA/设备信息、按版本分支常用）。
 * 必须为 JVM 真静态字段——壳 jar 原始字节码经 dex2jar 保留 getstatic 语义，
 * Kotlin object 的 val 编译为实例字段会 NoSuchFieldError（饭太硬壳读 CPU_ABI 实测）。
 */
public class Build {

    public static final String UNKNOWN = "unknown";

    public static final String MODEL = "ShipingDesktop";
    public static final String BRAND = UNKNOWN;
    public static final String MANUFACTURER = UNKNOWN;
    public static final String DEVICE = UNKNOWN;
    public static final String PRODUCT = UNKNOWN;
    public static final String HARDWARE = UNKNOWN;
    public static final String DISPLAY = UNKNOWN;
    public static final String BOARD = UNKNOWN;
    public static final String BOOTLOADER = UNKNOWN;
    public static final String HOST = "shiping-desktop";
    public static final String ID = "shiping";
    public static final String TAGS = UNKNOWN;
    public static final String TYPE = UNKNOWN;
    public static final String USER = "shiping";
    public static final String RADIO = UNKNOWN;
    public static final String SERIAL = UNKNOWN;
    public static final String FINGERPRINT = "shiping/desktop";
    public static final String CPU_ABI = "arm64-v8a";
    public static final String CPU_ABI2 = "armeabi-v7a";
    public static final String[] SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86"};
    public static final String[] SUPPORTED_32_BIT_ABIS = {"armeabi-v7a", "x86"};
    public static final String[] SUPPORTED_64_BIT_ABIS = {"arm64-v8a"};
    public static final boolean IS_DEBUGGABLE = false;

    /** 版本信息嵌套类（Build$VERSION） */
    public static class VERSION {
        public static final int SDK_INT = 30;
        public static final String RELEASE = "11";
        public static final String INCREMENTAL = UNKNOWN;
        public static final String CODENAME = "REL";
    }

    /** 版本代号常量（Build$VERSION_CODES，取部分常用值） */
    public static class VERSION_CODES {
        public static final int BASE = 1;
        public static final int O = 26;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
    }
}
