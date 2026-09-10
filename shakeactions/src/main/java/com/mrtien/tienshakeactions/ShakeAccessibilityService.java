package com.mrtien.tienshakeactions;

import android.accessibilityservice.AccessibilityService;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.accessibility.AccessibilityEvent;

/**
 * Nhận biết 2 loại cử chỉ:
 * 1) Lắc 1/2 lần.
 * 2) Ngửa màn hình lên trời để Back.
 *
 * "Ngửa" chỉ kích hoạt khi điện thoại trước đó đã ở tư thế đứng/nghiêng đủ lâu.
 * Vì vậy điện thoại nằm ngửa sẵn trên bàn sẽ không Back lặp đi lặp lại.
 */
public class ShakeAccessibilityService extends AccessibilityService implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastPeakAt;
    private long lastActionAt;
    private int pendingPeaks;
    private Runnable pendingSingle;

    private boolean faceUpArmed;
    private long nonFaceUpSince;
    private long faceUpCandidateSince;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values.length < 3) return;

        double gX = event.values[0] / SensorManager.GRAVITY_EARTH;
        double gY = event.values[1] / SensorManager.GRAVITY_EARTH;
        double gZ = event.values[2] / SensorManager.GRAVITY_EARTH;
        double magnitude = Math.sqrt(gX * gX + gY * gY + gZ * gZ);
        long now = SystemClock.elapsedRealtime();

        boolean faceUpEnabled = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("face_up_back", true);

        if (faceUpEnabled && handleFaceUp(gZ, magnitude, now)) {
            return;
        }

        handleShake(magnitude, now);
    }

    /**
     * @return true khi vừa thực hiện Back do cử chỉ ngửa.
     */
    private boolean handleFaceUp(double gZ, double magnitude, long now) {
        int sensitivity = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("face_up_sensitivity", 65);

        // Độ nhạy cao -> không cần ngửa phẳng hoàn toàn.
        double triggerZ = 0.90 - sensitivity * 0.0035;
        if (triggerZ < 0.56) triggerZ = 0.56;
        if (triggerZ > 0.88) triggerZ = 0.88;

        double armZ = triggerZ - 0.25;
        if (armZ < 0.28) armZ = 0.28;

        boolean stableGravity = magnitude > 0.72 && magnitude < 1.38;

        // Chỉ "nạp" cử chỉ sau khi máy đã đứng/nghiêng khoảng 220ms.
        if (gZ < armZ && stableGravity) {
            if (nonFaceUpSince == 0L) {
                nonFaceUpSince = now;
            }
            if (now - nonFaceUpSince >= 220L) {
                faceUpArmed = true;
            }
            faceUpCandidateSince = 0L;
            return false;
        }

        if (gZ >= triggerZ && stableGravity) {
            nonFaceUpSince = 0L;

            if (!faceUpArmed) {
                faceUpCandidateSince = 0L;
                return false;
            }

            if (faceUpCandidateSince == 0L) {
                faceUpCandidateSince = now;
                return false;
            }

            // Giữ tư thế ngửa trong thời gian rất ngắn để loại rung/lắc thoáng qua.
            if (now - faceUpCandidateSince >= 150L && now - lastActionAt >= 900L) {
                if (pendingSingle != null) {
                    handler.removeCallbacks(pendingSingle);
                    pendingSingle = null;
                    pendingPeaks = 0;
                }

                performGlobalAction(GLOBAL_ACTION_BACK);
                lastActionAt = now;
                faceUpArmed = false;
                faceUpCandidateSince = 0L;
                vibrate();
                return true;
            }
            return false;
        }

        // Ở vùng trung gian thì giữ trạng thái đã arm, nhưng hủy ứng viên ngửa.
        faceUpCandidateSince = 0L;
        return false;
    }

    private void handleShake(double magnitude, long now) {
        int sensitivity = getSharedPreferences("prefs", MODE_PRIVATE).getInt("sensitivity", 60);
        double threshold = 3.25 - (sensitivity * 0.016);
        if (threshold < 1.55) threshold = 1.55;
        if (threshold > 3.2) threshold = 3.2;

        if (magnitude < threshold) return;
        if (now - lastPeakAt < 220L) return;
        if (now - lastActionAt < 850L) return;

        lastPeakAt = now;
        pendingPeaks++;

        if (pendingPeaks == 1) {
            pendingSingle = () -> {
                if (pendingPeaks == 1) {
                    executeConfigured("single_action", "BACK");
                }
                pendingPeaks = 0;
                pendingSingle = null;
            };
            handler.postDelayed(pendingSingle, 520L);
        } else {
            if (pendingSingle != null) handler.removeCallbacks(pendingSingle);
            pendingSingle = null;
            pendingPeaks = 0;
            executeConfigured("double_action", "SCREENSHOT");
        }
    }

    private void executeConfigured(String prefKey, String fallback) {
        String action = getSharedPreferences("prefs", MODE_PRIVATE).getString(prefKey, fallback);
        int global = -1;
        if ("BACK".equals(action)) global = GLOBAL_ACTION_BACK;
        else if ("HOME".equals(action)) global = GLOBAL_ACTION_HOME;
        else if ("RECENTS".equals(action)) global = GLOBAL_ACTION_RECENTS;
        else if ("NOTIFICATIONS".equals(action)) global = GLOBAL_ACTION_NOTIFICATIONS;
        else if ("QUICK_SETTINGS".equals(action)) global = GLOBAL_ACTION_QUICK_SETTINGS;
        else if ("SCREENSHOT".equals(action)) global = GLOBAL_ACTION_TAKE_SCREENSHOT;
        else if ("NONE".equals(action)) return;

        if (global != -1) {
            performGlobalAction(global);
            lastActionAt = SystemClock.elapsedRealtime();
            vibrate();
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(
                        35L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                ));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.onDestroy();
    }
}
