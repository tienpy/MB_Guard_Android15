package com.mrtien.tienshakeactions;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Gyroscope gesture engine with per-device gesture learning.
 *
 * The phone's physical gyro axes do not always match a user's intuitive idea of
 * "face up / left / right" because hand grip and device posture differ. Instead
 * of guessing, every named gesture can learn one of the six raw gyro signatures:
 * X+, X-, Y+, Y-, Z+, Z-.
 */
public class ShakeAccessibilityService extends AccessibilityService
        implements SensorEventListener {

    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    private static final String[] GESTURE_KEYS = {
            "gesture_face_up",
            "gesture_face_down",
            "gesture_tilt_left",
            "gesture_tilt_right",
            "gesture_twist_left",
            "gesture_twist_right"
    };

    private static final String[] GESTURE_LABELS = {
            "Ngửa máy",
            "Cúi máy",
            "Nghiêng trái",
            "Nghiêng phải",
            "Xoay trái",
            "Xoay phải"
    };

    private static final String[] MAP_KEYS = {
            "map_face_up",
            "map_face_down",
            "map_tilt_left",
            "map_tilt_right",
            "map_twist_left",
            "map_twist_right"
    };

    private static final String[] DEFAULT_SIGNATURES = {
            "X+",
            "X-",
            "Y-",
            "Y+",
            "Z-",
            "Z+"
    };

    private SensorManager sensorManager;
    private Sensor gyroscope;

    private long previousSensorTimestampNs;
    private boolean armed;
    private long stillSinceMs;

    private boolean tracking;
    private int trackingAxis = -1;
    private double integratedAngleRad;
    private long gestureStartedMs;
    private long lastMeaningfulMotionMs;
    private long cooldownUntilMs;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        migrateV201();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            return;
        }

        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyroscope != null) {
            sensorManager.registerListener(
                    this,
                    gyroscope,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }

        resetDetector();
    }

    private void migrateV201() {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        if (prefs.getInt("gesture_engine_version", 0) >= 201) {
            return;
        }

        prefs.edit()
                .putInt("gesture_engine_version", 201)
                .putInt("gyro_sensitivity", 60)
                .putInt("cooldown_ms", 700)
                .remove("learn_gesture")
                .remove("invert_pitch")
                .remove("invert_roll")
                .remove("invert_yaw")
                .putString("last_gesture", "Hãy bấm HỌC NGỬA MÁY rồi thực hiện cử chỉ")
                .putLong("last_gesture_time", System.currentTimeMillis())
                .apply();
    }

    private void resetDetector() {
        previousSensorTimestampNs = 0L;
        armed = false;
        stillSinceMs = 0L;
        tracking = false;
        trackingAxis = -1;
        integratedAngleRad = 0.0;
        gestureStartedMs = 0L;
        lastMeaningfulMotionMs = 0L;
        cooldownUntilMs = 0L;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null
                || event.sensor.getType() != Sensor.TYPE_GYROSCOPE
                || event.values.length < 3) {
            return;
        }

        final long nowMs = SystemClock.elapsedRealtime();

        if (previousSensorTimestampNs == 0L) {
            previousSensorTimestampNs = event.timestamp;
            return;
        }

        double dt = (event.timestamp - previousSensorTimestampNs) / 1_000_000_000.0;
        previousSensorTimestampNs = event.timestamp;

        if (dt <= 0.0 || dt > 0.08) {
            return;
        }

        final double x = event.values[0];
        final double y = event.values[1];
        final double z = event.values[2];

        final double ax = Math.abs(x);
        final double ay = Math.abs(y);
        final double az = Math.abs(z);
        final double max = Math.max(ax, Math.max(ay, az));

        int sensitivity = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("gyro_sensitivity", 60);

        double triggerDegrees = 37.0 - (sensitivity * 0.22);
        if (triggerDegrees < 14.0) triggerDegrees = 14.0;
        if (triggerDegrees > 36.0) triggerDegrees = 36.0;
        final double triggerRadians = Math.toRadians(triggerDegrees);

        double startSpeed = 1.85 - (sensitivity * 0.012);
        if (startSpeed < 0.58) startSpeed = 0.58;
        if (startSpeed > 1.80) startSpeed = 1.80;

        final double stillSpeed = 0.22;
        final double meaningfulSpeed = 0.30;

        if (!tracking) {
            if (max < stillSpeed) {
                if (stillSinceMs == 0L) {
                    stillSinceMs = nowMs;
                }
                if (nowMs - stillSinceMs >= 380L && nowMs >= cooldownUntilMs) {
                    armed = true;
                }
            } else {
                stillSinceMs = 0L;
            }

            if (!armed || nowMs < cooldownUntilMs || max < startSpeed) {
                return;
            }

            int dominant = dominantAxis(ax, ay, az);
            double first = axisAbs(dominant, ax, ay, az);
            double second = secondLargest(dominant, ax, ay, az);

            // Reject diagonal/noisy motion unless one axis is clearly dominant.
            if (second > 0.0 && first < second * 1.22) {
                return;
            }

            tracking = true;
            armed = false;
            trackingAxis = dominant;
            integratedAngleRad = 0.0;
            gestureStartedMs = nowMs;
            lastMeaningfulMotionMs = nowMs;
            stillSinceMs = 0L;
        }

        if (tracking) {
            double axisSpeed = axisValue(trackingAxis, x, y, z);
            integratedAngleRad += axisSpeed * dt;

            if (Math.abs(axisSpeed) >= meaningfulSpeed) {
                lastMeaningfulMotionMs = nowMs;
            }

            if (Math.abs(integratedAngleRad) >= triggerRadians
                    && nowMs - gestureStartedMs >= 80L) {
                int direction = integratedAngleRad >= 0.0 ? 1 : -1;
                int axis = trackingAxis;
                finishGesture(nowMs);
                onRawGesture(signature(axis, direction));
                return;
            }

            if (nowMs - gestureStartedMs > 1000L
                    || nowMs - lastMeaningfulMotionMs > 200L) {
                cancelTracking();
            }
        }
    }

    private void finishGesture(long nowMs) {
        cancelTracking();

        int cooldown = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("cooldown_ms", 700);
        if (cooldown < 300) cooldown = 300;
        if (cooldown > 1500) cooldown = 1500;
        cooldownUntilMs = nowMs + cooldown;
    }

    private void cancelTracking() {
        tracking = false;
        trackingAxis = -1;
        integratedAngleRad = 0.0;
        gestureStartedMs = 0L;
        lastMeaningfulMotionMs = 0L;
        stillSinceMs = 0L;
    }

    private void onRawGesture(String rawSignature) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String learningGesture = prefs.getString("learn_gesture", "");

        if (learningGesture != null && !learningGesture.isEmpty()) {
            learnGesture(learningGesture, rawSignature);
            return;
        }

        int index = ownerIndexForSignature(rawSignature);
        if (index < 0) {
            return;
        }

        String gestureKey = GESTURE_KEYS[index];
        String gestureLabel = GESTURE_LABELS[index];

        prefs.edit()
                .putString("last_gesture", gestureLabel)
                .putLong("last_gesture_time", System.currentTimeMillis())
                .apply();

        String action = prefs.getString(gestureKey, "NONE");
        executeAction(action);
    }

    private void learnGesture(String gestureKey, String newSignature) {
        int targetIndex = indexForGestureKey(gestureKey);
        if (targetIndex < 0) {
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .remove("learn_gesture")
                    .apply();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String oldSignature = getSignatureForIndex(targetIndex);
        int previousOwner = ownerIndexForSignature(newSignature);

        SharedPreferences.Editor editor = prefs.edit();

        // Swap mappings so every raw direction always belongs to exactly one named gesture.
        if (previousOwner >= 0 && previousOwner != targetIndex) {
            editor.putString(MAP_KEYS[previousOwner], oldSignature);
        }

        editor.putString(MAP_KEYS[targetIndex], newSignature);
        editor.remove("learn_gesture");
        editor.putString(
                "last_gesture",
                "ĐÃ HỌC: " + GESTURE_LABELS[targetIndex]
        );
        editor.putLong("last_gesture_time", System.currentTimeMillis());
        editor.apply();

        longVibrate();
        Toast.makeText(
                this,
                "Đã học " + GESTURE_LABELS[targetIndex] + ". Bây giờ thử lại.",
                Toast.LENGTH_LONG
        ).show();
    }

    private int ownerIndexForSignature(String signature) {
        for (int i = 0; i < MAP_KEYS.length; i++) {
            if (signature.equals(getSignatureForIndex(i))) {
                return i;
            }
        }
        return -1;
    }

    private String getSignatureForIndex(int index) {
        return getSharedPreferences("prefs", MODE_PRIVATE)
                .getString(MAP_KEYS[index], DEFAULT_SIGNATURES[index]);
    }

    private int indexForGestureKey(String key) {
        for (int i = 0; i < GESTURE_KEYS.length; i++) {
            if (GESTURE_KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private String signature(int axis, int direction) {
        String axisName = axis == AXIS_X ? "X" : axis == AXIS_Y ? "Y" : "Z";
        return axisName + (direction >= 0 ? "+" : "-");
    }

    private void executeAction(String action) {
        int global = -1;

        if ("BACK".equals(action)) {
            global = GLOBAL_ACTION_BACK;
        } else if ("HOME".equals(action)) {
            global = GLOBAL_ACTION_HOME;
        } else if ("RECENTS".equals(action)) {
            global = GLOBAL_ACTION_RECENTS;
        } else if ("NOTIFICATIONS".equals(action)) {
            global = GLOBAL_ACTION_NOTIFICATIONS;
        } else if ("QUICK_SETTINGS".equals(action)) {
            global = GLOBAL_ACTION_QUICK_SETTINGS;
        } else if ("SCREENSHOT".equals(action)) {
            global = GLOBAL_ACTION_TAKE_SCREENSHOT;
        } else {
            return;
        }

        boolean ok = performGlobalAction(global);
        if (ok && getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("vibrate_feedback", true)) {
            shortVibrate();
        }
    }

    private int dominantAxis(double x, double y, double z) {
        if (x >= y && x >= z) return AXIS_X;
        if (y >= x && y >= z) return AXIS_Y;
        return AXIS_Z;
    }

    private double axisAbs(int axis, double x, double y, double z) {
        if (axis == AXIS_X) return x;
        if (axis == AXIS_Y) return y;
        return z;
    }

    private double secondLargest(int dominant, double x, double y, double z) {
        if (dominant == AXIS_X) return Math.max(y, z);
        if (dominant == AXIS_Y) return Math.max(x, z);
        return Math.max(x, y);
    }

    private double axisValue(int axis, double x, double y, double z) {
        if (axis == AXIS_X) return x;
        if (axis == AXIS_Y) return y;
        return z;
    }

    private void shortVibrate() {
        vibrate(28L);
    }

    private void longVibrate() {
        vibrate(100L);
    }

    private void vibrate(long durationMs) {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        durationMs,
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
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.onDestroy();
    }
}
