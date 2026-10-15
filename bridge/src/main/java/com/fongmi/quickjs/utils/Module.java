package com.fongmi.quickjs.utils;

import androidx.collection.LruCache;

import com.github.catvod.net.OkHttp;

public class Module {

    private static final int MAX_SIZE = 50;
    private final LruCache<String, String> cache;

    public Module() {
        cache = new LruCache<>(MAX_SIZE);
    }

    public static Module get() {
        return Loader.INSTANCE;
    }

    public String fetch(String name) {
        String content = cache.get(name);
        if (content != null && !content.isEmpty()) return content;
        if (name.startsWith("http")) {
            // 404/网络错误的响应体会被当脚本编译出各种怪错——非 200 视为拉取失败（空内容）
            try (okhttp3.Response res = OkHttp.client().newCall(new okhttp3.Request.Builder().url(name).build()).execute()) {
                content = res.isSuccessful() ? res.body().string() : "";
            } catch (Exception e) {
                content = "";
            }
            cache.put(name, content);
        } else if (name.startsWith("assets")) {
            // Android 真资源优先；桌面无 assets，回退 classpath 同路径（assets://js/lib/X → js/lib/X）
            content = com.github.catvod.utils.Asset.read(name);
            if (content.isEmpty()) content = Resource.read(name.replaceFirst("assets://", ""));
            cache.put(name, content);
        } else if (name.startsWith("lib/")) {
            // 双通道：classpath（桌面 bridge resources）优先，Android 回退 assets/js/lib
            content = Resource.read("js/" + name);
            if (content.isEmpty()) content = com.github.catvod.utils.Asset.read("js/" + name);
            cache.put(name, content);
        }
        return content;
    }

    public void clear() {
        cache.evictAll();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}
