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
        if (name.startsWith("http")) cache.put(name, content = OkHttp.string(name));
        else if (name.startsWith("assets")) cache.put(name, content = com.github.catvod.utils.Asset.read(name));
        else if (name.startsWith("lib/")) {
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
