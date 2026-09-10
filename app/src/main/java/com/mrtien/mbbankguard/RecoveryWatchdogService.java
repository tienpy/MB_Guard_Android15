package com.mrtien.mbbankguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.Set;

/**
 * Chỉ chạy sau cú bấm thủ công. Khi dùng biểu tượng chung, watchdog chờ một trong
 * các ứng dụng đã chọn xuất hiện; khi dùng lối tắt riêng, watchdog chờ đúng ứng dụng đó.
 * Rời ứng dụng được bảo vệ thì Accessibility được bật lại và service tự dừng.
 */
public class RecoveryWatchdogService extends Service {
    private static final String ACTION_ARM = "com.mrtien.mbbankguard.action.ARM_WATCHDOG";
    private static final String ACTION_STOP_AND_RESTORE =
            "com.mrtien.mbbankguard.action.STOP_WATCHDOG_AND_RESTORE";
    private static final String ACTION_CANCEL_WITHOUT_RESTORE =
            "com.mrtien.mbbankguard.action.CANCEL_WATCHDOG_WITHOUT_RESTORE";
    private static final String EXTRA_PACKAGE = "protected_package";

    private static final String PREFS = "guard_prefs";
    private static final String KEY_ARMED = "recovery_watchdog_armed";
    private static final String KEY_PACKAGE = "recovery_watchdog_package";

    private static final String CHANNEL_ID = "mb_guard_manual_protection";
    private static final int NOTIFICATION_ID = 2401;
    private static final long POLL_INTERVAL_MS = 1_500L;
    private static final long FIRST_OPEN_GRACE_MS = 60_000L;
    private static final long RESTORE_DELAY_MS = 3_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private boolean receiverRegistered;
    private boolean stopping;
    private boolean protectedAppSeen;
    private long armedAt;
    private long lastProtectedSeenAt;
    private String requestedPackage = "";
    private String activeProtectedPackage = "";

    private final Runnable pollRunnable = this::pollOnce;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                restoreAndStop("Màn hình đã tắt");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        startForeground(
                NOTIFICATION_ID,
                buildNotification("Accessibility đang được tắt tạm thời…")
        );
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_AND_RESTORE.equals(action)) {
            restoreAndStop("Đã bật lại thủ công");
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL_WITHOUT_RESTORE.equals(action)) {
            stopWithoutRestore();
            return START_NOT_STICKY;
        }

        if (ACTION_ARM.equals(action)) {
            String requested = intent.getStringExtra(EXTRA_PACKAGE);
            requestedPackage = requested == null ? "" : requested.trim();
            storeArmedState(this, true, requestedPackage);
            resetObservationWindow();
            updateWaitingNotification();
            handler.removeCallbacks(pollRunnable);
            handler.post(pollRunnable);
            return START_STICKY;
        }

        if (isArmed(this) && AccessibilityController.isGuardActive(this)) {
            requestedPackage = getStoredPackage(this);
            resetObservationWindow();
            updateWaitingNotification();
            handler.removeCallbacks(pollRunnable);
            handler.post(pollRunnable);
            return START_STICKY;
        }

        stopWithoutRestore();
        return START_NOT_STICKY;
    }

    private void resetObservationWindow() {
        armedAt = SystemClock.elapsedRealtime();
        lastProtectedSeenAt = 0L;
        protectedAppSeen = false;
        activeProtectedPackage = "";
    }

    private void updateWaitingNotification() {
        if (TextUtils.isEmpty(requestedPackage)) {
            updateNotification("Đã tắt Accessibility – hãy mở một ứng dụng đã chọn");
        } else {
            updateNotification("Đang chờ mở "
                    + MonitoredAppsStore.getAppLabel(this, requestedPackage));
        }
    }

    private void pollOnce() {
        if (stopping) {
            return;
        }
        if (!isArmed(this)) {
            if (AccessibilityController.isGuardActive(this)) {
                restoreAndStop("Mất trạng thái watchdog");
            } else {
                stopWithoutRestore();
            }
            return;
        }
        if (!AccessibilityController.isGuardActive(this)) {
            if (SystemClock.elapsedRealtime() - armedAt < 5_000L) {
                handler.postDelayed(pollRunnable, 250L);
            } else {
                stopWithoutRestore();
            }
            return;
        }
        if (!ForegroundAppDetector.hasUsageAccess(this)) {
            restoreAndStop("Mất quyền Usage Access");
            return;
        }

        String foregroundPackage = ForegroundAppDetector.getForegroundPackage(this);
        long now = SystemClock.elapsedRealtime();
        boolean isProtectedForeground = isProtectedForeground(foregroundPackage);

        if (isProtectedForeground) {
            protectedAppSeen = true;
            activeProtectedPackage = foregroundPackage;
            lastProtectedSeenAt = now;
            updateNotification("Đang bảo vệ "
                    + MonitoredAppsStore.getAppLabel(this, foregroundPackage));
        } else if (!protectedAppSeen) {
            if (now - armedAt >= FIRST_OPEN_GRACE_MS) {
                restoreAndStop("Không mở ứng dụng đã chọn trong 60 giây");
                return;
            }
        } else if (now - lastProtectedSeenAt >= RESTORE_DELAY_MS) {
            restoreAndStop("Đã thoát "
                    + MonitoredAppsStore.getAppLabel(this, activeProtectedPackage));
            return;
        }

        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private boolean isProtectedForeground(String foregroundPackage) {
        if (TextUtils.isEmpty(foregroundPackage)) {
            return false;
        }
        if (!TextUtils.isEmpty(requestedPackage)) {
            return requestedPackage.equals(foregroundPackage);
        }
        Set<String> selectedPackages = MonitoredAppsStore.getSelectedPackages(this);
        return selectedPackages.contains(foregroundPackage);
    }

    private void restoreAndStop(String reason) {
        if (stopping) {
            return;
        }
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        AccessibilityController.Result result =
                AccessibilityController.restoreSelectedServices(this);
        LauncherIconController.sync(this);
        storeArmedState(this, false, null);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_restore_message", reason + " – " + result.message)
                .apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopWithoutRestore() {
        if (stopping) {
            return;
        }
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        storeArmedState(this, false, null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MB Guard – đang bảo vệ tạm thời",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(
                "Chỉ chạy sau cú bấm thủ công và tự dừng sau khi bật lại Accessibility."
        );
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                21,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, RecoveryWatchdogService.class)
                .setAction(ACTION_STOP_AND_RESTORE);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                22,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle("MB Guard")
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat_shield,
                        "Bật lại ngay",
                        stopPendingIntent
                ).build())
                .build();
    }

    private void updateNotification(String text) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void registerScreenReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterScreenReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterScreenReceiver();
        if (!stopping && isArmed(this) && AccessibilityController.isGuardActive(this)) {
            AccessibilityController.restoreSelectedServices(this);
            storeArmedState(this, false, null);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean arm(Context context, String packageName) {
        if (!ForegroundAppDetector.hasUsageAccess(context)
                || MonitoredAppsStore.getSelectedPackages(context).isEmpty()) {
            return false;
        }
        String requested = packageName == null ? "" : packageName.trim();
        if (!requested.isEmpty()
                && !MonitoredAppsStore.getSelectedPackages(context).contains(requested)) {
            return false;
        }
        storeArmedState(context, true, requested);
        Intent intent = new Intent(context, RecoveryWatchdogService.class)
                .setAction(ACTION_ARM)
                .putExtra(EXTRA_PACKAGE, requested);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (Exception exception) {
            storeArmedState(context, false, null);
            return false;
        }
    }

    public static void stopAndRestore(Context context) {
        Intent intent = new Intent(context, RecoveryWatchdogService.class)
                .setAction(ACTION_STOP_AND_RESTORE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception exception) {
            AccessibilityController.restoreSelectedServices(context);
            storeArmedState(context, false, null);
        }
    }

    public static void cancelWithoutRestore(Context context) {
        storeArmedState(context, false, null);
        try {
            context.stopService(new Intent(context, RecoveryWatchdogService.class));
        } catch (Exception ignored) {
        }
    }

    public static boolean isArmed(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ARMED, false);
    }

    private static String getStoredPackage(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PACKAGE, "");
    }

    private static void storeArmedState(
            Context context,
            boolean armed,
            String packageName
    ) {
        android.content.SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_ARMED, armed);
        if (armed && !TextUtils.isEmpty(packageName)) {
            editor.putString(KEY_PACKAGE, packageName);
        } else {
            editor.remove(KEY_PACKAGE);
        }
        editor.commit();
    }
}
