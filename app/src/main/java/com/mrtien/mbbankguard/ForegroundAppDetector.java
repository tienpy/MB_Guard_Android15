package com.mrtien.mbbankguard;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import java.util.List;

/**
 * Chỉ được gọi trong lúc một ứng dụng đang được bảo vệ. Không có vòng lặp Usage Stats
 * khi Guard đang ở trạng thái chờ, nhờ đó giảm đáng kể mức dùng pin.
 */
public final class ForegroundAppDetector {
    private ForegroundAppDetector() {
    }

    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public static String getForegroundPackage(Context context) {
        if (!hasUsageAccess(context)) {
            return null;
        }

        UsageStatsManager manager =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        String packageName = findLatestResumedPackage(manager, now - 12_000L, now);
        if (packageName != null) {
            return packageName;
        }

        List<UsageStats> stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 60_000L,
                now
        );
        if (stats == null || stats.isEmpty()) {
            return null;
        }

        UsageStats latest = null;
        for (UsageStats item : stats) {
            if (latest == null || item.getLastTimeUsed() > latest.getLastTimeUsed()) {
                latest = item;
            }
        }
        return latest == null ? null : latest.getPackageName();
    }

    private static String findLatestResumedPackage(
            UsageStatsManager manager,
            long begin,
            long end
    ) {
        UsageEvents events = manager.queryEvents(begin, end);
        if (events == null) {
            return null;
        }

        UsageEvents.Event event = new UsageEvents.Event();
        String latestPackage = null;
        long latestTimestamp = Long.MIN_VALUE;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if ((type == UsageEvents.Event.ACTIVITY_RESUMED
                    || type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    && event.getTimeStamp() >= latestTimestamp) {
                latestTimestamp = event.getTimeStamp();
                latestPackage = event.getPackageName();
            }
        }
        return latestPackage;
    }
}
