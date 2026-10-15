package com.fongmi.chaquo;

import android.content.Context;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面版 Python Spider：经 PythonBridge 子进程分发调用，协议与 chaquo app.py 对齐
 * （FongMi 移植，api 为 drpy .py 脚本地址）。
 */
public class Spider extends com.github.catvod.crawler.Spider {

    private final String api;
    private final Gson gson;
    private volatile boolean loaded;

    public Spider(String api) {
        this.api = api;
        this.gson = new Gson();
    }

    private PythonBridge bridge() {
        return PythonBridge.get();
    }

    private Object call(String method, Object... args) {
        ensureLoaded();
        return bridge().call(api, method, args);
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            bridge().load(api, Path.py().getAbsolutePath());
            loaded = true;
        }
    }

    @Override
    public void init(Context context, String extend) {
        ensureLoaded();
        // 依赖声明为可选能力（部分 drpy py 脚本未实现 getDependence，缺失时忽略）
        try {
            Object dependence = bridge().call(api, "getDependence");
            if (dependence instanceof List<?> list) for (Object item : list) download(item + ".py");
        } catch (Exception ignored) {
        }
        bridge().call(api, "init", extend == null ? "" : extend);
    }

    private void download(String name) {
        try {
            File target = Path.py(name);
            if (target.exists()) return;
            String content = OkHttp.string(UriUtil.resolve(api, name));
            Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String homeContent(boolean filter) {
        return String.valueOf(call("homeContent", filter));
    }

    @Override
    public String homeVideoContent() {
        return String.valueOf(call("homeVideoContent"));
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return String.valueOf(call("categoryContent", tid, pg, filter, gson.toJson(extend)));
    }

    @Override
    public String detailContent(List<String> ids) {
        return String.valueOf(call("detailContent", gson.toJson(ids)));
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return String.valueOf(call("searchContent", key, quick));
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return String.valueOf(call("searchContent", key, quick, pg));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return String.valueOf(call("playerContent", flag, id, gson.toJson(vipFlags)));
    }

    @Override
    public String liveContent(String url) {
        return String.valueOf(call("liveContent", url));
    }

    @Override
    public boolean manualVideoCheck() {
        return Boolean.TRUE.equals(call("manualVideoCheck"));
    }

    @Override
    public boolean isVideoFormat(String url) {
        return Boolean.TRUE.equals(call("isVideoFormat", url));
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        Object raw = call("localProxy", gson.toJson(params));
        if (!(raw instanceof List<?> list) || list.size() < 3) return null;
        boolean base64 = list.size() > 4 && "1".equals(String.valueOf(list.get(4)));
        Object headerRaw = list.size() > 3 ? list.get(3) : null;
        Object[] result = new Object[4];
        result[0] = (int) Double.parseDouble(String.valueOf(list.get(0)));
        result[1] = String.valueOf(list.get(1));
        result[2] = getStream(list.get(2), base64);
        result[3] = getHeader(headerRaw);
        return result;
    }

    @Override
    public String action(String action) {
        Object result = call("action", action);
        return result == null ? null : String.valueOf(result);
    }

    @Override
    public void destroy() {
        try {
            call("destroy");
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> getHeader(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        try {
            Map<String, String> header = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) header.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            return header;
        } catch (Exception e) {
            return null;
        }
    }

    private ByteArrayInputStream getStream(Object raw, boolean base64) {
        if (raw == null) return null;
        byte[] data;
        if (raw instanceof List<?> list) {
            data = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) data[i] = (byte) (double) Double.parseDouble(String.valueOf(list.get(i)));
        } else {
            String content = String.valueOf(raw);
            if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
            data = base64 ? Util.decode(content) : content.getBytes(StandardCharsets.UTF_8);
        }
        return new ByteArrayInputStream(data);
    }
}
