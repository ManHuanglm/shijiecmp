package com.fongmi.quickjs.utils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * classpath 资源读取（替代 Android assets）：js 引擎库文件（js/lib/*.js）随 bridge jar 打包，
 * 双端（桌面 JVM / Android APK）统一从 classpath 读取。
 */
public class Resource {

    public static InputStream open(String name) {
        return Resource.class.getClassLoader().getResourceAsStream(name);
    }

    public static String read(String name) {
        try (InputStream is = open(name)) {
            if (is == null) return "";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) out.write(buffer, 0, len);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
