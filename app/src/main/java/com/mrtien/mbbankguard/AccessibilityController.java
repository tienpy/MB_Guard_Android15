package com.mrtien.mbbankguard;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AccessibilityController {
    private static final String PREFS = "guard_prefs";
    private static final String KEY_SELECTED = "selected_accessibility_components";
    private static final String KEY_RESTORE = "restore_accessibility_components";
    private static final String KEY_GUARD_ACTIVE = "guard_active";

    private AccessibilityController() {
    }

    public static final class InstalledService {
        public final String label;
        public final ComponentName componentName;
        public final boolean enabled;

        InstalledService(String label, ComponentName componentName, boolean enabled) {
            this.label = label;
            this.componentName = componentName;
            this.enabled = enabled;
        }

        public String flattened() {
            return componentName.flattenToString();
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final int targetCount;
        public final int changedCount;

        Result(boolean success, String message, int targetCount, int changedCount) {
            this.success = success;
            this.message = message;
            this.targetCount = targetCount;
            this.changedCount = changedCount;
        }
    }

    public static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static synchronized boolean removeLegacyGuardMonitor(Context context) {
        if (!hasWriteSecureSettings(context)) {
            return false;
        }
        LinkedHashSet<ComponentName> enabled =
                new LinkedHashSet<>(readEnabledComponents(context));
        ComponentName legacy = new ComponentName(
                context.getPackageName(),
                context.getPackageName() + ".GuardAccessibilityService"
        );
        if (!enabled.remove(legacy)) {
            return false;
        }
        try {
            writeEnabledComponents(context, enabled);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static List<InstalledService> getInstalledServices(Context context) {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return Collections.emptyList();
        }

        Set<ComponentName> enabled = readEnabledComponents(context);
        List<InstalledService> result = new ArrayList<>();
        PackageManager packageManager = context.getPackageManager();

        for (AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
            ResolveInfo resolveInfo = info.getResolveInfo();
            if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                continue;
            }
            String packageName = resolveInfo.serviceInfo.packageName;
            String className = resolveInfo.serviceInfo.name;
            if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(className)) {
                continue;
            }
            ComponentName component = new ComponentName(packageName, className);
            CharSequence loadedLabel = resolveInfo.loadLabel(packageManager);
            String label = loadedLabel == null
                    ? component.flattenToShortString()
                    : loadedLabel.toString();
            result.add(new InstalledService(label, component, enabled.contains(component)));
        }

        result.sort((left, right) -> left.label.compareToIgnoreCase(right.label));
        return result;
    }

    public static Set<ComponentName> getSelectedComponents(Context context) {
        List<InstalledService> installed = getInstalledServices(context);
        Map<String, ComponentName> installedByName = new LinkedHashMap<>();
        for (InstalledService service : installed) {
            installedByName.put(service.flattened(), service.componentName);
        }

        Set<String> saved = preferences(context).getStringSet(KEY_SELECTED, Collections.emptySet());
        LinkedHashSet<ComponentName> selected = new LinkedHashSet<>();
        if (saved != null) {
            for (String flattened : saved) {
                ComponentName component = installedByName.get(flattened);
                if (component != null) {
                    selected.add(component);
                }
            }
        }
        return selected;
    }

    public static void saveSelectedComponents(Context context, Set<ComponentName> components) {
        preferences(context).edit()
                .putStringSet(KEY_SELECTED, flatten(components == null
                        ? Collections.emptySet()
                        : components))
                .apply();
    }

    public static int getEnabledSelectedCount(Context context) {
        Set<ComponentName> selected = getSelectedComponents(context);
        Set<ComponentName> enabled = readEnabledComponents(context);
        int count = 0;
        for (ComponentName component : selected) {
            if (enabled.contains(component)) {
                count++;
            }
        }
        return count;
    }

    public static synchronized Result disableSelectedServices(Context context) {
        if (!hasWriteSecureSettings(context)) {
            return new Result(false,
                    "Chưa có quyền WRITE_SECURE_SETTINGS. Hãy bấm CẤP QUYỀN TRÊN ĐIỆN THOẠI.",
                    0, 0);
        }

        LinkedHashSet<ComponentName> targets =
                new LinkedHashSet<>(getSelectedComponents(context));
        if (targets.isEmpty()) {
            return new Result(false,
                    "Chưa chọn dịch vụ Accessibility nào cần tắt.",
                    0, 0);
        }
        if (isGuardActive(context)) {
            return new Result(true,
                    "Các dịch vụ đã ở trạng thái tắt tạm thời.",
                    targets.size(), 0);
        }

        LinkedHashSet<ComponentName> enabled =
                new LinkedHashSet<>(readEnabledComponents(context));
        enabled.remove(new ComponentName(
                context.getPackageName(),
                context.getPackageName() + ".GuardAccessibilityService"
        ));
        LinkedHashSet<ComponentName> restoreSnapshot = new LinkedHashSet<>(enabled);
        restoreSnapshot.retainAll(targets);

        if (restoreSnapshot.isEmpty()) {
            preferences(context).edit()
                    .putBoolean(KEY_GUARD_ACTIVE, false)
                    .remove(KEY_RESTORE)
                    .apply();
            return new Result(false,
                    "Các Trợ năng đã chọn hiện đang OFF, nên không có gì để tắt. "
                            + "Hãy bấm BẬT/TẮT TRỢ NĂNG để bật chúng trước rồi thử MỞ APP AN TOÀN.",
                    targets.size(), 0);
        }

        int before = enabled.size();
        enabled.removeAll(targets);
        int changed = before - enabled.size();

        try {
            preferences(context).edit()
                    .putStringSet(KEY_RESTORE, flatten(restoreSnapshot))
                    .putBoolean(KEY_GUARD_ACTIVE, true)
                    .commit();
            writeEnabledComponents(context, enabled);
            return new Result(true,
                    "Đã tắt tạm thời " + changed + "/" + targets.size() + " dịch vụ.",
                    targets.size(), changed);
        } catch (Exception exception) {
            preferences(context).edit()
                    .putBoolean(KEY_GUARD_ACTIVE, false)
                    .remove(KEY_RESTORE)
                    .apply();
            return new Result(false,
                    "Không thể tắt dịch vụ: " + safeMessage(exception),
                    targets.size(), 0);
        }
    }

    public static synchronized Result restoreSelectedServices(Context context) {
        if (!hasWriteSecureSettings(context)) {
            return new Result(false,
                    "Chưa có quyền WRITE_SECURE_SETTINGS nên không thể bật lại dịch vụ.",
                    0, 0);
        }

        SharedPreferences preferences = preferences(context);
        boolean hasSnapshot = preferences.contains(KEY_RESTORE);
        Set<String> saved = preferences.getStringSet(KEY_RESTORE, Collections.emptySet());
        LinkedHashSet<ComponentName> restore = parseComponents(saved);

        if (!hasSnapshot && isGuardActive(context)) {
            restore.addAll(getSelectedComponents(context));
        }
        restore.retainAll(getAllInstalledComponents(context));

        if (restore.isEmpty()) {
            preferences.edit()
                    .putBoolean(KEY_GUARD_ACTIVE, false)
                    .remove(KEY_RESTORE)
                    .apply();
            return new Result(true, "Không có dịch vụ nào cần bật lại.", 0, 0);
        }

        LinkedHashSet<ComponentName> enabled =
                new LinkedHashSet<>(readEnabledComponents(context));
        enabled.remove(new ComponentName(
                context.getPackageName(),
                context.getPackageName() + ".GuardAccessibilityService"
        ));
        int before = enabled.size();
        enabled.addAll(restore);
        int changed = enabled.size() - before;

        try {
            writeEnabledComponents(context, enabled);
            preferences.edit()
                    .putBoolean(KEY_GUARD_ACTIVE, false)
                    .remove(KEY_RESTORE)
                    .apply();
            return new Result(true,
                    "Đã bật lại " + restore.size() + " dịch vụ trước đó đang ON.",
                    restore.size(), changed);
        } catch (Exception exception) {
            return new Result(false,
                    "Không thể bật lại dịch vụ: " + safeMessage(exception),
                    restore.size(), 0);
        }
    }

    public static synchronized Result toggleSelectedServicesManually(Context context) {
        if (!hasWriteSecureSettings(context)) {
            return new Result(false,
                    "Chưa có quyền WRITE_SECURE_SETTINGS. Hãy bấm CẤP QUYỀN TRÊN ĐIỆN THOẠI.",
                    0, 0);
        }

        LinkedHashSet<ComponentName> selected =
                new LinkedHashSet<>(getSelectedComponents(context));
        if (selected.isEmpty()) {
            return new Result(false,
                    "Chưa chọn dịch vụ Trợ năng nào. Hãy mở MB Guard Cài đặt và chọn trước.",
                    0, 0);
        }

        preferences(context).edit()
                .putBoolean(KEY_GUARD_ACTIVE, false)
                .remove(KEY_RESTORE)
                .commit();

        LinkedHashSet<ComponentName> enabled =
                new LinkedHashSet<>(readEnabledComponents(context));
        boolean anySelectedEnabled = false;
        for (ComponentName component : selected) {
            if (enabled.contains(component)) {
                anySelectedEnabled = true;
                break;
            }
        }

        try {
            if (anySelectedEnabled) {
                int before = enabled.size();
                enabled.removeAll(selected);
                int changed = before - enabled.size();
                writeEnabledComponents(context, enabled);
                return new Result(true,
                        "ĐÃ TẮT " + changed + "/" + selected.size()
                                + " dịch vụ Trợ năng đã chọn.",
                        selected.size(), changed);
            }

            int before = enabled.size();
            enabled.addAll(selected);
            int changed = enabled.size() - before;
            writeEnabledComponents(context, enabled);
            return new Result(true,
                    "ĐÃ BẬT " + selected.size() + " dịch vụ Trợ năng đã chọn.",
                    selected.size(), changed);
        } catch (Exception exception) {
            return new Result(false,
                    "Không thể bật/tắt Trợ năng: " + safeMessage(exception),
                    selected.size(), 0);
        }
    }

    public static boolean isGuardActive(Context context) {
        return preferences(context).getBoolean(KEY_GUARD_ACTIVE, false);
    }

    private static Set<ComponentName> getAllInstalledComponents(Context context) {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        LinkedHashSet<ComponentName> result = new LinkedHashSet<>();
        if (manager == null) {
            return result;
        }
        for (AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
            ResolveInfo resolveInfo = info.getResolveInfo();
            if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                continue;
            }
            String packageName = resolveInfo.serviceInfo.packageName;
            String className = resolveInfo.serviceInfo.name;
            if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(className)) {
                result.add(new ComponentName(packageName, className));
            }
        }
        return result;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<ComponentName> readEnabledComponents(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        LinkedHashSet<ComponentName> result = new LinkedHashSet<>();
        if (TextUtils.isEmpty(enabled)) {
            return result;
        }
        for (String part : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(part);
            if (component != null) {
                result.add(component);
            }
        }
        return result;
    }

    private static void writeEnabledComponents(Context context, Set<ComponentName> components) {
        List<String> flattened = new ArrayList<>();
        for (ComponentName component : components) {
            flattened.add(component.flattenToString());
        }
        String joined = TextUtils.join(":", flattened);

        if (components.isEmpty()) {
            boolean servicesWritten = Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    joined
            );
            notifySecureSetting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            SystemClock.sleep(80L);

            boolean globalWritten = Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
            );
            notifySecureSetting(context, Settings.Secure.ACCESSIBILITY_ENABLED);

            if (!servicesWritten || !globalWritten) {
                throw new IllegalStateException("Secure Settings trả về false");
            }
            return;
        }

        // Android 15/OEMs can fail to bind a service if the service list is written while
        // ACCESSIBILITY_ENABLED is still 0. Wake the global manager first, then write the
        // list, then assert the global flag again.
        boolean globalFirst = Settings.Secure.putInt(
                context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
        );
        notifySecureSetting(context, Settings.Secure.ACCESSIBILITY_ENABLED);
        SystemClock.sleep(120L);

        boolean servicesWritten = Settings.Secure.putString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                joined
        );
        notifySecureSetting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        SystemClock.sleep(180L);

        boolean globalLast = Settings.Secure.putInt(
                context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
        );
        notifySecureSetting(context, Settings.Secure.ACCESSIBILITY_ENABLED);
        notifySecureSetting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (!globalFirst || !servicesWritten || !globalLast) {
            throw new IllegalStateException("Secure Settings trả về false");
        }

        if (waitForManagerToSeeAll(context, components, 1200L)) {
            return;
        }

        // Strong fallback for Android 15/OEM builds: pulse the global accessibility
        // manager without changing the service list. This forces AccessibilityManagerService
        // to reload/bind the services that are already present in the secure setting.
        Settings.Secure.putInt(
                context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
        );
        notifySecureSetting(context, Settings.Secure.ACCESSIBILITY_ENABLED);
        SystemClock.sleep(180L);

        Settings.Secure.putString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                joined
        );
        notifySecureSetting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        SystemClock.sleep(120L);

        Settings.Secure.putInt(
                context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
        );
        notifySecureSetting(context, Settings.Secure.ACCESSIBILITY_ENABLED);
        notifySecureSetting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (!waitForManagerToSeeAll(context, components, 1800L)) {
            throw new IllegalStateException(
                    "Android đã ghi trạng thái ON nhưng chưa khởi động lại dịch vụ Trợ năng."
            );
        }
    }

    private static void notifySecureSetting(Context context, String key) {
        try {
            Uri uri = Settings.Secure.getUriFor(key);
            context.getContentResolver().notifyChange(uri, null);
        } catch (Throwable ignored) {
        }
    }

    private static boolean waitForManagerToSeeAll(
            Context context,
            Set<ComponentName> expected,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            Set<ComponentName> managerEnabled = readManagerEnabledComponents(context);
            if (managerEnabled.containsAll(expected)) {
                return true;
            }
            SystemClock.sleep(120L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private static Set<ComponentName> readManagerEnabledComponents(Context context) {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        LinkedHashSet<ComponentName> result = new LinkedHashSet<>();
        if (manager == null) {
            return result;
        }
        List<AccessibilityServiceInfo> enabled =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) {
            return result;
        }
        for (AccessibilityServiceInfo info : enabled) {
            if (info == null || info.getResolveInfo() == null
                    || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            String packageName = info.getResolveInfo().serviceInfo.packageName;
            String className = info.getResolveInfo().serviceInfo.name;
            if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(className)) {
                result.add(new ComponentName(packageName, className));
            }
        }
        return result;
    }

    private static LinkedHashSet<String> flatten(Set<ComponentName> components) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ComponentName component : components) {
            if (component != null) {
                result.add(component.flattenToString());
            }
        }
        return result;
    }

    private static LinkedHashSet<ComponentName> parseComponents(Set<String> flattened) {
        LinkedHashSet<ComponentName> result = new LinkedHashSet<>();
        if (flattened != null) {
            for (String value : flattened) {
                ComponentName component = ComponentName.unflattenFromString(value);
                if (component != null) {
                    result.add(component);
                }
            }
        }
        return result;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return TextUtils.isEmpty(message) ? exception.getClass().getSimpleName() : message;
    }
}
