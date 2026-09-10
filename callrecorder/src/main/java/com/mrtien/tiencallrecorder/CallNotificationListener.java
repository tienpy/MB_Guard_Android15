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
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null) {
            return;
        }

        String title = text(notification.extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = text(notification.extras.getCharSequence(Notification.EXTRA_TEXT));
        String big = text(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String combined = (title + " " + body + " " + big).toLowerCase(Locale.ROOT);

        boolean categoryCall = Notification.CATEGORY_CALL.equals(notification.category);
        boolean knownVoip = isKnownVoipPackage(sbn.getPackageName());

        if (categoryCall || (knownVoip && looksLikeCall(combined, notification))) {
            activeCallNotifications.add(sbn.getKey());
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .putString(
                            "last_event",
                            "Thông báo cuộc gọi: " + sourceForPackage(sbn.getPackageName())
                    )
                    .putLong("last_event_time", System.currentTimeMillis())
                    .apply();

            signal(
                    MonitorRecorderService.ACTION_VOIP_START,
                    sourceForPackage(sbn.getPackageName())
            );
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }

        if (activeCallNotifications.remove(sbn.getKey())
                && activeCallNotifications.isEmpty()) {
            signal(MonitorRecorderService.ACTION_VOIP_STOP, "");
        }
    }

    private void signal(String action, String source) {
        if (!getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("enabled", false)) {
            return;
        }

        Intent intent = new Intent(this, MonitorRecorderService.class)
                .setAction(action);
        intent.putExtra("source", source);

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .putString(
                            "last_error",
                            "Không gửi được sự kiện cuộc gọi từ thông báo: "
                                    + t.getClass().getSimpleName()
                    )
                    .putLong("last_error_time", System.currentTimeMillis())
                    .apply();
        }
    }

    private boolean isKnownVoipPackage(String packageName) {
        return "com.zing.zalo".equals(packageName)
                || "com.facebook.orca".equals(packageName)
                || "com.facebook.katana".equals(packageName)
                || "com.whatsapp".equals(packageName)
                || "org.telegram.messenger".equals(packageName)
                || "com.viber.voip".equals(packageName);
    }

    private String sourceForPackage(String packageName) {
        if ("com.zing.zalo".equals(packageName)) return "ZALO";
        if ("com.facebook.orca".equals(packageName)) return "MESSENGER";
        if ("com.facebook.katana".equals(packageName)) return "FACEBOOK";
        if ("com.whatsapp".equals(packageName)) return "WHATSAPP";
        if ("org.telegram.messenger".equals(packageName)) return "TELEGRAM";
        if ("com.viber.voip".equals(packageName)) return "VIBER";
        return "APP_CALL";
    }

    private boolean looksLikeCall(String value, Notification notification) {
        if (Notification.CATEGORY_CALL.equals(notification.category)) {
            return true;
        }
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0
                && containsCallWord(value)) {
            return true;
        }
        return containsCallWord(value);
    }

    private boolean containsCallWord(String value) {
        return value.contains("cuộc gọi")
                || value.contains("đang gọi")
                || value.contains("gọi đến")
                || value.contains("gọi đi")
                || value.contains("đang trong cuộc gọi")
                || value.contains("cuộc gọi thoại")
                || value.contains("cuộc gọi video")
                || value.contains("incoming call")
                || value.contains("ongoing call")
                || value.contains("voice call")
                || value.contains("video call")
                || value.contains("calling")
                || value.contains("call in progress");
    }

    private String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
