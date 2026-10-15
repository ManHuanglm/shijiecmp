# ShijieCmp Android release 混淆规则（R8）

# ===== 爬虫生态桥（最高优先级，FQN 冻结）=====
# payload 爬虫 jar 由 DexClassLoader（parent-first）加载，jar 内 com.github.catvod.*
# 必须解析到宿主桥接类；SpiderRuntime 亦经 Class.forName("com.github.catvod.Proxy") 反射调用
-keep class com.github.catvod.** { *; }
# payload jar 内 Spider 子类继承宿主 Spider 基类并重写 homeContent 等公开方法
-keepclassmembers class * extends com.github.catvod.crawler.Spider {
    public <methods>;
}

# ===== 爬虫 jar 运行时依赖库（FQN 冻结）=====
# payload 爬虫 jar 打包时不含 okhttp/gson，运行时经 DexClassLoader（parent-first）
# 按原始类名解析宿主提供类；R8 改名将导致 NoClassDefFoundError 启动闪退
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
# zxing：App 自身只引用解码侧（QrCodeAnalyzer），编码侧 EncodeHintType/QRCodeWriter 无
# 引用会被 R8 移除；南风 Bili 爬虫扫码登录（checkLoginState false → 生成二维码）直接
# new EncodeHintType，缺类主线程 NoClassDefFoundError 闪退（2026-10-12 模拟器日志实证）
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
# quickjs JS 爬虫引擎（FongMi 移植）：Global 的 @JSMethod 反射注册、Local 经 wrapper
# newInstance 反射构造、Req/Res 走 Gson @SerializedName——混淆任一环即 JS 源全灭
# （2026-10-13 模拟器实证：Local has no zero argument constructor）
-keep class com.fongmi.quickjs.** { *; }
-dontwarn com.fongmi.quickjs.**
# Chaquopy PY 爬虫引擎：Python 侧 stub 经反射调用 Java 方法（Loader/Spider 与 com.chaquo
# 运行时），drpy 脚本按方法名约定调用 Spider 基类接口，混淆任一环 PY 源全灭
-keep class com.fongmi.chaquo.** { *; }
-dontwarn com.fongmi.chaquo.**
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ===== Gson 模型（@SerializedName 字段名即 JSON 契约，禁止混淆字段）=====
# shared jvmCommon TVBox/CMS 模型（ApiResponse/Vod/Category 等）
-keep class com.shiping.desktop.data.model.** { *; }
# composeApp androidMain Room 实体（Room 校验列名 + Gson 拷贝）
-keep class com.shiping.app.data.model.** { *; }

# 保留泛型签名：Retrofit 解析 suspend 方法返回类型（Continuation<ApiResponse<T>>）
# 与 Gson 反序列化泛型字段都依赖 Signature 属性；新版 R8 会剥离未显式保留的签名，
# 导致运行时 "Class cannot be cast to ParameterizedType"
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# Retrofit 官方规则
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Gson：TypeToken 泛型与 Type 实现
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type
