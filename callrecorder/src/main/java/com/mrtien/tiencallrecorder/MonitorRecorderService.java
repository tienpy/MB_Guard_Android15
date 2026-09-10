package com.mrtien.tiencallrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MonitorRecorderService extends Service {
    public static final String ACTION_ENABLE = "enable";
    public static final String ACTION_DISABLE = "disable";
    public static final String ACTION_MANUAL_START = "manual_start";
    public static final String ACTION_MANUAL_STOP = "manual_stop";
    public static final String ACTION_VOIP_START = "voip_start";
    public static final String ACTION_VOIP_STOP = "voip_stop";

    private static final String CHANNEL = "tien_call_recorder";
    private static volatile boolean recording;

    private MediaRecorder recorder;
    private TelephonyManager telephonyManager;
    private PhoneCallback phoneCallback;
    private AudioManager audioManager;
    private AudioDeviceInfo previousCommunicationDevice;
    private boolean speakerChangedByUs;
    private String activeSource = "";

    private final class PhoneCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            handlePhoneState(state);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(3001, buildNotification("Đang chờ cuộc gọi"));
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        registerPhoneState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_ENABLE : intent.getAction();
        if (ACTION_DISABLE.equals(action)) {
            stopRecording("Tắt tự động");
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_MANUAL_START.equals(action)) {
            startRecording(safeSource(intent == null ? null : intent.getStringExtra("source"), "TEST"));
        } else if (ACTION_MANUAL_STOP.equals(action)) {
            stopRecording("Dừng ghi thử");
        } else if (ACTION_VOIP_START.equals(action)) {
            if (getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("enabled", false)
                    && getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("auto_voip", true)) {
                startRecording(safeSource(intent == null ? null : intent.getStringExtra("source"), "VOIP"));
            }
        } else if (ACTION_VOIP_STOP.equals(action)) {
            if (activeSource.startsWith("ZALO") || activeSource.startsWith("MESSENGER")
                    || activeSource.startsWith("FACEBOOK") || activeSource.startsWith("WHATSAPP")
                    || activeSource.startsWith("VOIP")) {
                stopRecording("Cuộc gọi ứng dụng đã kết thúc");
            }
        } else {
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        }
        updateWaitingNotification();
        return START_STICKY;
    }

    private void registerPhoneState() {
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (telephonyManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                phoneCallback = new PhoneCallback();
                telephonyManager.registerTelephonyCallback(getMainExecutor(), phoneCallback);
            } else {
                telephonyManager.listen(new android.telephony.PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        handlePhoneState(state);
                    }
                }, android.telephony.PhoneStateListener.LISTEN_CALL_STATE);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handlePhoneState(int state) {
        if (!getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("enabled", false)
                || !getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("auto_phone", true)) {
            return;
        }
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            startRecording("PHONE");
        } else if (state == TelephonyManager.CALL_STATE_IDLE && "PHONE".equals(activeSource)) {
            stopRecording("Cuộc gọi điện thoại đã kết thúc");
        }
    }

    private synchronized void startRecording(String source) {
        if (recording) return;
        try {
            maybeEnableSpeaker();

            File base = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (base == null) base = getFilesDir();
            File dir = new File(base, "TienCallRecorder");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String clean = source.replaceAll("[^A-Za-z0-9_-]", "_");
            File currentFile = new File(dir, clean + "_" + stamp + ".m4a");

            if (Build.VERSION.SDK_INT >= 31) {
                recorder = new MediaRecorder(this);
            } else {
                recorder = new MediaRecorder();
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(48000);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            activeSource = source;
            recording = true;
            notifyText("ĐANG GHI: " + source);
        } catch (Throwable t) {
            releaseRecorder();
            restoreSpeaker();
            activeSource = "";
            recording = false;
            notifyText("Không mở được microphone để ghi");
        }
    }

    private synchronized void stopRecording(String reason) {
        if (!recording) {
            restoreSpeaker();
            updateWaitingNotification();
            return;
        }
        try {
            recorder.stop();
        } catch (Throwable ignored) {
        }
        releaseRecorder();
        restoreSpeaker();
        recording = false;
        activeSource = "";
        notifyText(reason);
        updateWaitingNotification();
    }

    private void maybeEnableSpeaker() {
        if (audioManager == null
                || !getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("speaker", false)) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                previousCommunicationDevice = audioManager.getCommunicationDevice();
                for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speakerChangedByUs = audioManager.setCommunicationDevice(d);
                        break;
                    }
                }
            } else {
                speakerChangedByUs = !audioManager.isSpeakerphoneOn();
                audioManager.setSpeakerphoneOn(true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void restoreSpeaker() {
        if (audioManager == null || !speakerChangedByUs) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (previousCommunicationDevice != null) {
                    audioManager.setCommunicationDevice(previousCommunicationDevice);
                } else {
                    audioManager.clearCommunicationDevice();
                }
            } else {
                audioManager.setSpeakerphoneOn(false);
            }
        } catch (Throwable ignored) {
        }
        speakerChangedByUs = false;
        previousCommunicationDevice = null;
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
        }
    }

    public static boolean isRecording() {
        return recording;
    }

    private String safeSource(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Tien Call Recorder", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Dịch vụ chờ và tự ghi khi có cuộc gọi.");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, MonitorRecorderService.class).setAction(ACTION_DISABLE);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("Tien Call Recorder")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat_mic, "Tắt tự động", stopPi).build())
                .build();
    }

    private void notifyText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(3001, buildNotification(text));
    }

    private void updateWaitingNotification() {
        if (!recording) notifyText("Đang chờ cuộc gọi");
    }

    @Override
    public void onDestroy() {
        stopRecording("Dịch vụ đã dừng");
        if (Build.VERSION.SDK_INT >= 31 && telephonyManager != null && phoneCallback != null) {
            try { telephonyManager.unregisterTelephonyCallback(phoneCallback); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
