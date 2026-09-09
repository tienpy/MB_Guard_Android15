package com.mrtien.mbbankguard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.text.TextUtils;

/** Tạo lối tắt riêng cho từng ứng dụng đã chọn. */
public final class HomeShortcutHelper {
    private HomeShortcutHelper() {
    }

    public static boolean isSupported(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
        return manager != null && manager.isRequestPinShortcutSupported();
    }

    public static boolean requestPinnedShortcut(
            Activity activity,
            MonitoredAppsStore.InstalledApp app
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || app == null
                || TextUtils.isEmpty(app.packageName)) {
            return false;
        }
        ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            return false;
        }

        Intent intent = new Intent(activity, SafeLaunchActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(SafeLaunchActivity.EXTRA_TARGET_PACKAGE, app.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        String shortLabel = app.label;
        if (shortLabel.length() > 24) {
            shortLabel = shortLabel.substring(0, 24);
        }
        ShortcutInfo shortcut = new ShortcutInfo.Builder(
                activity,
                "safe_" + Integer.toHexString(app.packageName.hashCode())
        )
                .setShortLabel(shortLabel)
                .setLongLabel("Mở an toàn " + app.label)
                .setIcon(Icon.createWithResource(activity, R.drawable.ic_launcher))
                .setIntent(intent)
                .build();
        return manager.requestPinShortcut(shortcut, null);
    }
}
