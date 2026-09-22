package com.github.catvod.crawler;

import android.text.TextUtils;
import android.util.Log;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    public static void log(Throwable th) {
        if (th != null) th.printStackTrace();
    }

    public static void log(String msg) {
        if (!TextUtils.isEmpty(msg)) Log.d(TAG, msg);
    }

    public static void log(String tag, String msg, Object... args) {
        if (!TextUtils.isEmpty(msg)) Log.d(tag, msg + (args.length > 0 ? java.util.Arrays.toString(args) : ""));
    }
}
