package com.mrtien.mbbankguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Đây là launcher icon DUY NHẤT của MB Guard Android 15.
 * Một lần bấm -> OFF các dịch vụ Trợ năng đã chọn.
 * Bấm lần nữa -> ON lại đúng các dịch vụ đó.
 *
 * Việc ghi/bind Accessibility trên Android 15 có thể mất vài giây nên xử lý
 * trên worker thread; activity trong suốt chỉ đóng sau khi hệ thống đã xác nhận.
 */
public class ToggleAccessibilityActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        RecoveryWatchdogService.cancelWithoutRestore(this);

        if (!AccessibilityController.hasWriteSecureSettings(this)
                || AccessibilityController.getSelectedComponents(this).isEmpty()) {
            openSettings();
            return;
        }

        final boolean turningOff =
                AccessibilityController.getEnabledSelectedCount(this) > 0;

        executor.execute(() -> {
            AccessibilityController.Result result =
                    AccessibilityController.toggleSelectedServicesManually(this);

            runOnUiThread(() -> {
                String message = result.message;
                if (result.success) {
                    int onCount = AccessibilityController.getEnabledSelectedCount(this);
                    if (turningOff) {
                        message = onCount == 0
                                ? "TRỢ NĂNG: OFF"
                                : "Android chưa tắt hết Trợ năng đã chọn.";
                    } else {
                        int selectedCount =
                                AccessibilityController.getSelectedComponents(this).size();
                        message = onCount == selectedCount
                                ? "TRỢ NĂNG: ON"
                                : "Android chưa bật lại Trợ năng. Hãy thử bấm lại.";
                    }
                }

                Toast.makeText(
                        this,
                        message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG
                ).show();

                finishAndRemoveTask();
            });
        });
    }

    private void openSettings() {
        Intent setupIntent = new Intent(this, MainActivity.class);
        setupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(setupIntent);
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
