package com.fongmi.chaquo;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.github.catvod.Init;
import com.github.catvod.utils.Path;

/**
 * Chaquopy Python 引擎装载器：启动内嵌 Python（官方 AndroidPlatform，assets 由插件打包），
 * 加载 app.py 模块并按 drpy 脚本地址创建 Python 侧 Spider 实例。
 */
public class Loader {

    private final PyObject app;

    public Loader() {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(Init.context()));
        app = Python.getInstance().getModule("app");
    }

    /** 加载 drpy 脚本（api 为 .py URL），返回 Python 侧实例（PyObject 包装由 Spider 持有） */
    public Spider spider(String api) {
        PyObject obj = app.callAttr("spider", Path.py().getAbsolutePath(), api);
        return new Spider(app, obj, api);
    }
}
