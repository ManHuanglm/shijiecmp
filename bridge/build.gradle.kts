// bridge：爬虫生态桥接层（纯 JVM 模块，Kotlin+Java 混编）。
// 为什么独立成模块：shared 模块使用 androidLibrary 插件，其工程级不可再应用 java 插件，
// jvmMain 中的 Java 源无法编译；catvod Java 桥接（FQN 冻结，payload 运行时反射调用）
// 与 android.* 桩必须保持 Java/Kotlin 原样，故拆到本纯 JVM 模块，由 shared jvmMain 依赖。
// 仅桌面目标消费本模块（Android 端用系统真实 android.* 类，绝不能依赖此桩层）。

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    // catvod OkHttp 桥（net.OkHttp/OkDns/Spider.java 等）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // catvod Json 桥接使用 org.json：必须用 android-json（Android org.json 移植版，
    // 宽松 getString 语义），官方 JSON-java 严格抛错会破坏 payload 爬虫解析
    implementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    // org.json 宽松语义/Bean 序列化
    implementation("com.google.code.gson:gson:2.10.1")
    // catvod net.OkHttp 使用 androidx.collection.ArrayMap（纯 JVM 实现）
    implementation("androidx.collection:collection:1.2.0")
    implementation("androidx.annotation:annotation:1.6.0")
    // FtyGuardEngine：JVM 内模拟 ARM64 执行加固壳 so（饭太硬 ftyguard）
    implementation("com.github.zhkl0228.unidbg:unidbg-android:0.9.8")
}
