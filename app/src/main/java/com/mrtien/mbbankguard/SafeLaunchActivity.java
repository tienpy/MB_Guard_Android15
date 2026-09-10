package com.mrtien.mbbankguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.Set;

/**
 * Activity trong suốt của biểu tượng ngoài màn hình chính.
 * Bấm biểu tượng chung: tắt Accessibility ngay rồi chờ người dùng tự mở một ứng dụng đã chọn.
 * Bấm lối tắt riêng: tắt Accessibility và mở thẳng ứng dụng tương ứng.
 */
public class SafeLaunchActivity extends Activity {
    public static final String EXTRA_TARGET_PACKAGE = "target_package";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activateProtection();
    }

    private void activateProtection() {
        AccessibilityController.removeLegacyGuardMonitor(this);

        Set<String> selectedPackages = MonitoredAppsStore.getSelectedPackages(this);
        String targetPackage = getIntent() == null
                ? ""
                : getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        if (targetPackage == null) {
            targetPackage = "";
        }
        targetPackage = targetPackage.trim();

        if (selectedPackages.isEmpty()) {
            failSilently("Chưa chọn ứng dụng cần bảo vệ trong MB Guard Cài đặt.");
            return;
        }
        if (!TextUtils.isEmpty(targetPackage) && !selectedPackages.contains(targetPackage)) {
            failSilently("Ứng dụng của lối tắt không còn nằm trong danh sách được bảo vệ.");
            return;
        }
        if (!AccessibilityController.hasWriteSecureSettings(this)) {
            failSilently("Chưa cấp WRITE_SECURE_SETTINGS bằng ADB.");
            return;
        }
        if (!ForegroundAppDetector.hasUsageAccess(this)) {
            failSilently("Chưa cấp quyền Usage Access.");
            return;
        }
        if (AccessibilityController.getSelectedComponents(this).isEmpty()) {
            failSilently("Chưa chọn Accessibility cần tắt trong MB Guard Cài đặt.");
            return;
        }
        if (AccessibilityController.getEnabledSelectedCount(this) == 0) {
            failSilently("Các Trợ năng đã chọn hiện đang OFF. Hãy bật chúng bằng nút BẬT/TẮT TRỢ NĂNG trước.");
            return;
        }

        Intent launchIntent = null;
        if (!TextUtils.isEmpty(targetPackage)) {
            launchIntent = getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (launchIntent == null) {
                failSilently("Không tìm thấy ứng dụng của lối tắt trên máy.");
                return;
            }
        }

        if (AccessibilityController.isGuardActive(this)
                && !RecoveryWatchdogService.isArmed(this)) {
            AccessibilityController.restoreSelectedServices(this);
        }

        if (AccessibilityController.isGuardActive(this)
                && RecoveryWatchdogService.isArmed(this)) {
            if (launchIntent != null) {
                openTarget(launchIntent);
            } else {
                closeShortcutTask();
            }
            return;
        }

        if (!RecoveryWatchdogService.arm(this, targetPackage)) {
            failSilently("Không khởi động được bộ tự bật lại.");
            return;
        }

        AccessibilityController.Result result =
                AccessibilityController.disableSelectedServices(this);
        if (!result.success) {
            RecoveryWatchdogService.stopAndRestore(this);
            failSilently(result.message);
            return;
        }

        if (launchIntent != null) {
            openTarget(launchIntent);
        } else {
            Toast.makeText(
                    this,
                    "Đã tắt tạm thời Accessibility. Hãy mở ứng dụng cần dùng.",
                    Toast.LENGTH_SHORT
            ).show();
            closeShortcutTask();
        }
    }

    private void openTarget(Intent launchIntent) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(launchIntent);
        closeShortcutTask();
    }

    private void failSilently(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        closeShortcutTask();
    }

    private void closeShortcutTask() {
        finishAndRemoveTask();
    }
}
