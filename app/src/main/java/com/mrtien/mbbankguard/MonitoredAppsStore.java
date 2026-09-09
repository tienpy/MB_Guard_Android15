package com.mrtien.mbbankguard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lưu nhiều ứng dụng có thể được bảo vệ bởi lối tắt một chạm. */
public final class MonitoredAppsStore {
    private static final String PREFS = "guard_prefs";
    private static final String KEY_SELECTED_PACKAGES = "selected_monitored_packages";
    private static final String LEGACY_KEY_QUICK_PACKAGE = "quick_launch_package";
    private static final String DEFAULT_PACKAGE = "com.mbmobile";

    private MonitoredAppsStore() {
    }

    public static final class InstalledApp {
        public final String label;
        public final String packageName;

        InstalledApp(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    public static List<InstalledApp> getLaunchableApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved;
        if (Build.VERSION.SDK_INT >= 33) {
            resolved = packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0)
            );
        } else {
            resolved = packageManager.queryIntentActivities(launcherIntent, 0);
        }

        Map<String, InstalledApp> unique = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info == null || info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (TextUtils.isEmpty(packageName) || packageName.equals(context.getPackageName())) {
                continue;
            }
            CharSequence loadedLabel = info.loadLabel(packageManager);
            String label = loadedLabel == null ? packageName : loadedLabel.toString().trim();
            if (TextUtils.isEmpty(label)) {
                label = packageName;
            }
            unique.put(packageName, new InstalledApp(label, packageName));
        }

        List<InstalledApp> result = new ArrayList<>(unique.values());
        result.sort((left, right) -> {
            int byLabel = left.label.compareToIgnoreCase(right.label);
            return byLabel != 0
                    ? byLabel
                    : left.packageName.compareToIgnoreCase(right.packageName);
        });
        return result;
    }

    public static LinkedHashSet<String> getSelectedPackages(Context context) {
        SharedPreferences preferences = preferences(context);
        Set<String> saved = preferences.getStringSet(
                KEY_SELECTED_PACKAGES,
                Collections.emptySet()
        );
        LinkedHashSet<String> selected = clean(context, saved);

        // Di chuyển cấu hình một ứng dụng của bản 1.4.0 sang danh sách nhiều ứng dụng.
        if (selected.isEmpty() && !preferences.contains(KEY_SELECTED_PACKAGES)) {
            String legacyQuick = preferences.getString(LEGACY_KEY_QUICK_PACKAGE, "");
            if (!TextUtils.isEmpty(legacyQuick)
                    && !legacyQuick.equals(context.getPackageName())) {
                selected.add(legacyQuick);
            }
            if (selected.isEmpty()) {
                selected.add(DEFAULT_PACKAGE);
            }
            saveSelectedPackages(context, selected);
        }
        return selected;
    }

    public static void saveSelectedPackages(Context context, Set<String> packageNames) {
        LinkedHashSet<String> cleaned = clean(context, packageNames);
        preferences(context).edit()
                .putStringSet(KEY_SELECTED_PACKAGES, cleaned)
                .remove(LEGACY_KEY_QUICK_PACKAGE)
                .apply();
    }

    public static boolean isMonitored(Context context, String packageName) {
        return !TextUtils.isEmpty(packageName)
                && getSelectedPackages(context).contains(packageName);
    }

    public static String getAppLabel(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return "ứng dụng";
        }
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0)
                );
            } else {
                info = packageManager.getApplicationInfo(packageName, 0);
            }
            CharSequence label = packageManager.getApplicationLabel(info);
            return TextUtils.isEmpty(label) ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException exception) {
            return packageName;
        }
    }

    public static List<InstalledApp> getSelectedInstalledApps(Context context) {
        Set<String> selected = getSelectedPackages(context);
        List<InstalledApp> result = new ArrayList<>();
        Map<String, InstalledApp> installedByPackage = new LinkedHashMap<>();
        for (InstalledApp app : getLaunchableApps(context)) {
            installedByPackage.put(app.packageName, app);
        }
        for (String packageName : selected) {
            InstalledApp app = installedByPackage.get(packageName);
            result.add(app != null
                    ? app
                    : new InstalledApp(getAppLabel(context, packageName), packageName));
        }
        result.sort((left, right) -> left.label.compareToIgnoreCase(right.label));
        return result;
    }

    private static LinkedHashSet<String> clean(Context context, Set<String> packageNames) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        if (packageNames != null) {
            for (String packageName : packageNames) {
                if (!TextUtils.isEmpty(packageName)) {
                    String value = packageName.trim();
                    if (!value.isEmpty() && !value.equals(context.getPackageName())) {
                        cleaned.add(value);
                    }
                }
            }
        }
        return cleaned;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
