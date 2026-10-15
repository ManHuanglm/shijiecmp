package com.github.catvod.utils;

import com.github.catvod.Init;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 桌面 stub：python 脚本/jar 缓存目录（PythonBridge 解包 runner.py、下载爬虫 .py 与
 * ensureJar 校验的落点）。cache 根取 Init.context().getCacheDir()。
 */
public class Path {

    private static File cache() {
        File dir = new File(Init.context().getCacheDir(), "catvod");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File py() {
        File dir = new File(cache(), "py");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File py(String name) {
        return new File(py(), name);
    }

    public static File jar() {
        File dir = new File(cache(), "jar");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File jar(String name) {
        return new File(jar(), Crypto.md5(name).concat(".jar"));
    }

    public static String read(File file) {
        try (InputStream is = new FileInputStream(file)) {
            return read(is);
        } catch (Exception e) {
            return "";
        }
    }

    public static String read(InputStream is) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) out.write(buffer, 0, len);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static byte[] readToByte(InputStream is) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) out.write(buffer, 0, len);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static byte[] readToByte(File file) {
        try (InputStream is = new FileInputStream(file)) {
            return readToByte(is);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static void write(File file, InputStream is) {
        try (FileOutputStream os = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
