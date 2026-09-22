package android.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面 stub：android.app.ActivityThread。
 * payload 爬虫（如 YCyz 的 playerContent）经反射 currentActivityThread().mActivities
 * 迭代 ActivityClientRecord（反射取其 activity/paused 字段）拿当前 Activity；
 * 桌面端无真实 Activity 体系，预置一条记录保证反射链路走通。
 */
public class ActivityThread {

    /** 活动记录（对齐 Android ActivityThread$ActivityClientRecord 的反射面） */
    public static class ActivityClientRecord {

        public Activity activity;

        public boolean paused;
    }

    private static final ActivityThread sInstance = new ActivityThread();

    /** 活动记录表：payload 反射读取的 mActivities 字段 */
    public final Map<String, ActivityClientRecord> mActivities = new LinkedHashMap<>();

    private ActivityThread() {
        ActivityClientRecord record = new ActivityClientRecord();
        record.activity = new Activity();
        record.paused = false;
        mActivities.put("desktop", record);
    }

    public static ActivityThread currentActivityThread() {
        return sInstance;
    }
}
