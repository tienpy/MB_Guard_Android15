package com.mrtien.mbbankguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Biểu tượng thao tác nhanh ngoài màn hình chính.
 * Bấm một lần: tắt toàn bộ dịch vụ Trợ năng đã chọn đang bật.
 * Bấm lần nữa: bật lại toàn bộ dịch vụ Trợ năng đã chọn.
 */
public class ToggleAccessibilityActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        RecoveryWatchdogService.cancelWithoutRestore(this);

        AccessibilityController.Result result =
                AccessibilityController.toggleSelectedServicesManually(this);
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();

        if (!result.success
                && (!AccessibilityController.hasWriteSecureSettings(this)
                || AccessibilityController.getSelectedComponents(this).isEmpty())) {
            Intent setupIntent = new Intent(this, MainActivity.class);
            setupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(setupIntent);
        }

        finishAndRemoveTask();
    }
}
