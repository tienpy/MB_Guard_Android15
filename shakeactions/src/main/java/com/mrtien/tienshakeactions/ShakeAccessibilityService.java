package com.mrtien.tienshakeactions;

import android.accessibilityservice.AccessibilityService;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.accessibility.AccessibilityEvent;

/**
 * Gesture engine rebuilt around the phone gyroscope.
 *
 * A gesture is accepted only when:
 * 1. the phone has been relatively still long enough to ARM;
 * 2. one rotational axis becomes clearly dominant;
 * 3. the integrated angle crosses the configured threshold;
 * 4. after firing, the phone must become still again before another gesture can fire.
 *
 * This deliberately avoids accelerometer "shake" detection so ordinary hand movement,
 * walking, or putting the phone on a table does not create repeated Back actions.
 */
public class ShakeAccessibilityService extends AccessibilityService
        implements SensorEventListener {

    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

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

        armed = false;
        stillSinceMs = 0L;
        tracking = false;
        previousSensorTimestampNs = 0L;
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

        // Higher sensitivity means a smaller rotation angle and a lower start speed.
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
            // Re-arm only after the phone has settled. This is the main protection
            // against "jumping around" and repeated actions from one physical movement.
            if (max < stillSpeed) {
                if (stillSinceMs == 0L) {
                    stillSinceMs = nowMs;
                }
                if (nowMs - stillSinceMs >= 320L && nowMs >= cooldownUntilMs) {
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

            // Reject diagonal / chaotic movements. Micro-gesture style motion should
            // have one axis clearly stronger than the others.
            if (second > 0.0 && first < second * 1.28) {
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

            // Fire immediately once the requested rotational angle is reached.
            if (Math.abs(integratedAngleRad) >= triggerRadians
                    && nowMs - gestureStartedMs >= 80L) {
                int direction = integratedAngleRad >= 0.0 ? 1 : -1;
                finishGesture(nowMs);
                executeRotationGesture(trackingAxis, direction);
                return;
            }

            // A short motion that never crossed the angle threshold is cancelled.
            if (nowMs - gestureStartedMs > 950L
                    || nowMs - lastMeaningfulMotionMs > 180L) {
                tracking = false;
                trackingAxis = -1;
                integratedAngleRad = 0.0;
                stillSinceMs = 0L;
            }
        }
    }

    private void finishGesture(long nowMs) {
        tracking = false;
        trackingAxis = -1;
        integratedAngleRad = 0.0;
        stillSinceMs = 0L;

        int cooldown = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("cooldown_ms", 650);
        if (cooldown < 300) cooldown = 300;
        if (cooldown > 1500) cooldown = 1500;
        cooldownUntilMs = nowMs + cooldown;
    }

    private void executeRotationGesture(int axis, int direction) {
        boolean invertPitch = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("invert_pitch", false);
        boolean invertRoll = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("invert_roll", false);
        boolean invertYaw = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("invert_yaw", false);

        if (axis == AXIS_X && invertPitch) direction *= -1;
        if (axis == AXIS_Y && invertRoll) direction *= -1;
        if (axis == AXIS_Z && invertYaw) direction *= -1;

        final String gestureKey;
        final String gestureLabel;

        if (axis == AXIS_X) {
            if (direction > 0) {
                gestureKey = "gesture_face_up";
                gestureLabel = "Ngửa máy";
            } else {
                gestureKey = "gesture_face_down";
                gestureLabel = "Cúi máy";
            }
        } else if (axis == AXIS_Y) {
            if (direction > 0) {
                gestureKey = "gesture_tilt_right";
                gestureLabel = "Nghiêng phải";
            } else {
                gestureKey = "gesture_tilt_left";
                gestureLabel = "Nghiêng trái";
            }
        } else {
            if (direction > 0) {
                gestureKey = "gesture_twist_right";
                gestureLabel = "Xoay phải";
            } else {
                gestureKey = "gesture_twist_left";
                gestureLabel = "Xoay trái";
            }
        }

        String action = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString(gestureKey, "NONE");

        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("last_gesture", gestureLabel)
                .putLong("last_gesture_time", System.currentTimeMillis())
                .apply();

        executeAction(action);
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
            vibrate();
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

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        28L,
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
