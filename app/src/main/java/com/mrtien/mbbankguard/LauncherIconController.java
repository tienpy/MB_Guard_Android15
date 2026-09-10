package com.mrtien.mbbankguard;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Đổi màu launcher icon theo trạng thái THẬT của các dịch vụ Trợ năng đã chọn.
 * Xanh = tất cả dịch vụ đã chọn đang ON.
 * Đỏ = chưa chọn dịch vụ hoặc có ít nhất một dịch vụ đang OFF.
 */
public final class LauncherIconController {
    private static final String ON_ALIAS =
            "com.mrtien.mbbankguard.ToggleAccessibilityOnAlias";
    private static final String OFF_ALIAS =
            "com.mrtien.mbbankguard.ToggleAccessibilityOffAlias";

    private LauncherIconController() {
    }

    public static void sync(Context context) {
        int selectedCount = AccessibilityController.getSelectedComponents(context).size();
        int enabledCount = AccessibilityController.getEnabledSelectedCount(context);
        boolean allOn = selectedCount > 0 && enabledCount == selectedCount;
        setOn(context, allOn);
    }

    public static void setOn(Context context, boolean on) {
        PackageManager packageManager = context.getPackageManager();
        ComponentName onAlias = new ComponentName(context.getPackageName(), ON_ALIAS);
        ComponentName offAlias = new ComponentName(context.getPackageName(), OFF_ALIAS);

        int desiredOnState = on
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        int desiredOffState = on
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        int flags = PackageManager.DONT_KILL_APP;

        try {
            if (on) {
                // Enable the target first so the launcher never has a moment with zero icons.
                packageManager.setComponentEnabledSetting(onAlias, desiredOnState, flags);
                packageManager.setComponentEnabledSetting(offAlias, desiredOffState, flags);
            } else {
                packageManager.setComponentEnabledSetting(offAlias, desiredOffState, flags);
                packageManager.setComponentEnabledSetting(onAlias, desiredOnState, flags);
            }
        } catch (Throwable ignored) {
            // Không được làm hỏng thao tác bật/tắt Trợ năng chỉ vì launcher OEM
            // không cho đổi alias ngay lúc đó. Lần mở cài đặt sau sẽ sync lại.
        }
    }
}
