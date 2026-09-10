package com.mrtien.tiencallrecorder;

import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class CallNotificationListener extends NotificationListenerService {
    private final Set<String> activeCallNotifications = new HashSet<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isSupportedPackage(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;

        String title = text(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = text(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        String big = text(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String combined = (title + " " + body + " " + big).toLowerCase(Locale.ROOT);

        if (looksLikeCall(combined, n)) {
            activeCallNotifications.add(sbn.getKey());
            signal(MonitorRecorderService.ACTION_VOIP_START, sourceForPackage(sbn.getPackageName()));
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (activeCallNotifications.remove(sbn.getKey()) && activeCallNotifications.isEmpty()) {
            signal(MonitorRecorderService.ACTION_VOIP_STOP, "");
        }
    }

    private void signal(String action, String source) {
        if (!getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("enabled", false)) return;
        Intent i = new Intent(this, MonitorRecorderService.class).setAction(action);
        i.putExtra("source", source);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {
        }
    }

    private boolean isSupportedPackage(String p) {
        return "com.zing.zalo".equals(p)
                || "com.facebook.orca".equals(p)
                || "com.facebook.katana".equals(p)
                || "com.whatsapp".equals(p);
    }

    private String sourceForPackage(String p) {
        if ("com.zing.zalo".equals(p)) return "ZALO";
        if ("com.facebook.orca".equals(p)) return "MESSENGER";
        if ("com.facebook.katana".equals(p)) return "FACEBOOK";
        if ("com.whatsapp".equals(p)) return "WHATSAPP";
        return "VOIP";
    }

    private boolean looksLikeCall(String text, Notification n) {
        if (Notification.CATEGORY_CALL.equals(n.category)) return true;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 && containsCallWord(text)) return true;
        return containsCallWord(text);
    }

    private boolean containsCallWord(String s) {
        return s.contains("cuộc gọi")
                || s.contains("đang gọi")
                || s.contains("gọi đến")
                || s.contains("gọi đi")
                || s.contains("incoming call")
                || s.contains("ongoing call")
                || s.contains("voice call")
                || s.contains("video call")
                || s.contains("calling");
    }

    private String text(CharSequence c) {
        return c == null ? "" : c.toString();
    }
}
