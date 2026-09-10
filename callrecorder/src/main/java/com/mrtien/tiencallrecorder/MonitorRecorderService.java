package com.mrtien.tiencallrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
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
    private static final long AUDIO_POLL_MS = 500L;
    private static final long AUDIO_ACTIVE_CONFIRM_MS = 350L;
    private static final long AUDIO_INACTIVE_CONFIRM_MS = 1200L;
    private static final long RETRY_RECORD_MS = 1600L;

    private static volatile boolean recording;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable audioModePoller = new Runnable() {
        @Override
        public void run() {
            pollAudioMode();
            handler.postDelayed(this, AUDIO_POLL_MS);
        }
    };

    private MediaRecorder recorder;
    private TelephonyManager telephonyManager;
    private PhoneCallback phoneCallback;
    private AudioManager audioManager;
    private AudioDeviceInfo previousCommunicationDevice;
    private boolean speakerChangedByUs;
    private String activeSource = "";

    private boolean phoneOffhook;
    private long audioActiveSinceMs;
    private long audioInactiveSinceMs;
    private long nextRecordAttemptMs;

    private Uri currentMediaUri;
    private ParcelFileDescriptor currentPfd;
    private File currentPrivateFile;
    private String currentDisplayName;

    private final class PhoneCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
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
        handler.post(audioModePoller);
        setLastEvent("Dịch vụ tự động đang chạy và chờ cuộc gọi");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_ENABLE : intent.getAction();

        if (ACTION_DISABLE.equals(action)) {
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("enabled", false)
                    .apply();
            stopRecording("Đã tắt tự động ghi");
            setLastEvent("Đã tắt chế độ tự động");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_MANUAL_START.equals(action)) {
            setLastEvent("Người dùng bấm GHI THỬ NGAY");
            startRecording(safeSource(
                    intent == null ? null : intent.getStringExtra("source"),
                    "TEST"
            ));
        } else if (ACTION_MANUAL_STOP.equals(action)) {
            stopRecording("Đã dừng ghi thử");
        } else if (ACTION_VOIP_START.equals(action)) {
            setLastEvent("Phát hiện thông báo cuộc gọi ứng dụng");
            if (isAutoEnabled() && autoVoipEnabled()) {
                startRecording(safeSource(
                        intent == null ? null : intent.getStringExtra("source"),
                        "APP_CALL"
                ));
            }
        } else if (ACTION_VOIP_STOP.equals(action)) {
            if (isVoipSource(activeSource)) {
                stopRecording("Cuộc gọi ứng dụng đã kết thúc");
            }
        } else {
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("enabled", true)
                    .apply();
            setLastEvent("Đã bật chế độ tự động và đang chờ cuộc gọi");
        }

        updateWaitingNotification();
        return START_STICKY;
    }

    private void registerPhoneState() {
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (telephonyManager == null) {
            setLastError("Không lấy được TelephonyManager");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                phoneCallback = new PhoneCallback();
                telephonyManager.registerTelephonyCallback(
                        getMainExecutor(),
                        phoneCallback
                );
            } else {
                telephonyManager.listen(
                        new android.telephony.PhoneStateListener() {
                            @Override
                            public void onCallStateChanged(int state, String phoneNumber) {
                                handlePhoneState(state);
                            }
                        },
                        android.telephony.PhoneStateListener.LISTEN_CALL_STATE
                );
            }
        } catch (Throwable t) {
            setLastError("Không đăng ký được trạng thái cuộc gọi: " + shortError(t));
        }
    }

    private void handlePhoneState(int state) {
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            phoneOffhook = true;
            setLastEvent("Phát hiện cuộc gọi điện thoại đang kết nối");
            if (isAutoEnabled() && autoPhoneEnabled()) {
                startRecording("PHONE");
            }
        } else if (state == TelephonyManager.CALL_STATE_RINGING) {
            setLastEvent("Điện thoại đang đổ chuông");
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            boolean wasPhoneCall = phoneOffhook;
            phoneOffhook = false;
            if (wasPhoneCall) {
                setLastEvent("Cuộc gọi điện thoại đã kết thúc");
            }
            if ("PHONE".equals(activeSource)) {
                stopRecording("Cuộc gọi điện thoại đã kết thúc");
            }
        }
    }

    private void pollAudioMode() {
        if (audioManager == null) {
            return;
        }

        int mode;
        try {
            mode = audioManager.getMode();
        } catch (Throwable t) {
            setLastError("Không đọc được AudioMode: " + shortError(t));
            return;
        }

        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putInt("last_audio_mode", mode)
                .apply();

        boolean communicationActive = mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_CALL_SCREENING;

        long now = SystemClock.elapsedRealtime();

        if (communicationActive) {
            audioInactiveSinceMs = 0L;
            if (audioActiveSinceMs == 0L) {
                audioActiveSinceMs = now;
                setLastEvent("AudioManager phát hiện chế độ cuộc gọi: " + audioModeName(mode));
            }

            if (!isAutoEnabled() || recording || now < nextRecordAttemptMs) {
                return;
            }

            if (now - audioActiveSinceMs < AUDIO_ACTIVE_CONFIRM_MS) {
                return;
            }

            if (phoneOffhook) {
                if (autoPhoneEnabled()) {
                    if (!startRecording("PHONE")) {
                        nextRecordAttemptMs = now + RETRY_RECORD_MS;
                    }
                }
            } else if (autoVoipEnabled()) {
                if (!startRecording("APP_CALL")) {
                    nextRecordAttemptMs = now + RETRY_RECORD_MS;
                }
            }
            return;
        }

        audioActiveSinceMs = 0L;

        if (recording && ("APP_CALL".equals(activeSource) || isVoipSource(activeSource))) {
            if (audioInactiveSinceMs == 0L) {
                audioInactiveSinceMs = now;
            }
            if (now - audioInactiveSinceMs >= AUDIO_INACTIVE_CONFIRM_MS) {
                stopRecording("Chế độ âm thanh cuộc gọi đã kết thúc");
            }
        } else {
            audioInactiveSinceMs = 0L;
        }
    }

    private synchronized boolean startRecording(String source) {
        if (recording) {
            return true;
        }

        if (!isMicrophonePermissionGranted()) {
            setLastError("Chưa cấp quyền Microphone");
            notifyText("Chưa cấp quyền Microphone");
            return false;
        }

        try {
            maybeEnableSpeaker();
            prepareOutput(source);

            boolean prepared = tryPrepareRecorder(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
            );
            if (!prepared) {
                releaseRecorderOnly();
                prepared = tryPrepareRecorder(MediaRecorder.AudioSource.MIC);
            }

            if (!prepared || recorder == null) {
                throw new IllegalStateException("Không prepare được MediaRecorder");
            }

            recorder.start();
            activeSource = source;
            recording = true;
            nextRecordAttemptMs = 0L;

            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .putString("last_error", "")
                    .putString("last_file", currentDisplayName == null ? "" : currentDisplayName)
                    .putString("last_event", "ĐANG GHI: " + source)
                    .putLong("last_event_time", System.currentTimeMillis())
                    .apply();

            notifyText("ĐANG GHI: " + source);
            return true;
        } catch (Throwable t) {
            setLastError("Không mở được microphone để ghi: " + shortError(t));
            releaseRecorderOnly();
            deleteUnfinishedOutput();
            restoreSpeaker();
            activeSource = "";
            recording = false;
            notifyText("Chưa ghi được - app sẽ tự thử lại");
            return false;
        }
    }

    private boolean tryPrepareRecorder(int audioSource) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                recorder = new MediaRecorder(this);
            } else {
                recorder = new MediaRecorder();
            }

            recorder.setAudioSource(audioSource);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(48000);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioChannels(1);

            if (currentPfd != null) {
                recorder.setOutputFile(currentPfd.getFileDescriptor());
            } else if (currentPrivateFile != null) {
                recorder.setOutputFile(currentPrivateFile.getAbsolutePath());
            } else {
                throw new IllegalStateException("Chưa tạo được file đầu ra");
            }

            recorder.prepare();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void prepareOutput(String source) throws Exception {
        closeCurrentPfd();
        currentMediaUri = null;
        currentPrivateFile = null;

        String stamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new Date());
        String clean = source.replaceAll("[^A-Za-z0-9_-]", "_");
        currentDisplayName = clean + "_" + stamp + ".m4a";

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, currentDisplayName);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            values.put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/TienCallRecorder"
            );
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);

            currentMediaUri = getContentResolver().insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    values
            );

            if (currentMediaUri == null) {
                throw new IllegalStateException("MediaStore không tạo được file");
            }

            currentPfd = getContentResolver().openFileDescriptor(
                    currentMediaUri,
                    "w"
            );

            if (currentPfd == null) {
                throw new IllegalStateException("Không mở được file MediaStore");
            }
            return;
        }

        File base = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) {
            base = getFilesDir();
        }
        File dir = new File(base, "TienCallRecorder");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Không tạo được thư mục ghi âm");
        }
        currentPrivateFile = new File(dir, currentDisplayName);
    }

    private synchronized void stopRecording(String reason) {
        if (!recording) {
            restoreSpeaker();
            updateWaitingNotification();
            return;
        }

        boolean stopOk = true;
        try {
            recorder.stop();
        } catch (Throwable t) {
            stopOk = false;
            setLastError("File ghi âm bị lỗi khi dừng: " + shortError(t));
        }

        releaseRecorderOnly();

        if (stopOk) {
            finalizeOutput();
            setLastEvent(reason + " - đã lưu " + currentDisplayName);
        } else {
            deleteUnfinishedOutput();
        }

        restoreSpeaker();
        recording = false;
        activeSource = "";
        currentDisplayName = null;
        nextRecordAttemptMs = 0L;
        notifyText(reason);
        updateWaitingNotification();
    }

    private void finalizeOutput() {
        closeCurrentPfd();

        if (Build.VERSION.SDK_INT >= 29 && currentMediaUri != null) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                getContentResolver().update(
                        currentMediaUri,
                        values,
                        null,
                        null
                );
                getSharedPreferences("prefs", MODE_PRIVATE)
                        .edit()
                        .putString("last_file_uri", currentMediaUri.toString())
                        .apply();
            } catch (Throwable t) {
                setLastError("Đã ghi nhưng chưa hoàn tất MediaStore: " + shortError(t));
            }
        } else if (currentPrivateFile != null) {
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .putString("last_file_uri", Uri.fromFile(currentPrivateFile).toString())
                    .apply();
        }

        currentMediaUri = null;
        currentPrivateFile = null;
    }

    private void deleteUnfinishedOutput() {
        closeCurrentPfd();
        if (currentMediaUri != null) {
            try {
                getContentResolver().delete(currentMediaUri, null, null);
            } catch (Throwable ignored) {
            }
        }
        if (currentPrivateFile != null && currentPrivateFile.exists()) {
            try {
                currentPrivateFile.delete();
            } catch (Throwable ignored) {
            }
        }
        currentMediaUri = null;
        currentPrivateFile = null;
        currentDisplayName = null;
    }

    private void closeCurrentPfd() {
        if (currentPfd != null) {
            try {
                currentPfd.close();
            } catch (Throwable ignored) {
            }
            currentPfd = null;
        }
    }

    private void maybeEnableSpeaker() {
        if (audioManager == null
                || !getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("speaker", false)) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                previousCommunicationDevice = audioManager.getCommunicationDevice();
                for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speakerChangedByUs = audioManager.setCommunicationDevice(device);
                        break;
                    }
                }
            } else {
                speakerChangedByUs = !audioManager.isSpeakerphoneOn();
                audioManager.setSpeakerphoneOn(true);
            }
        } catch (Throwable t) {
            setLastError("Không tự bật được loa ngoài: " + shortError(t));
        }
    }

    private void restoreSpeaker() {
        if (audioManager == null || !speakerChangedByUs) {
            return;
        }

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

    private void releaseRecorderOnly() {
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (Throwable ignored) {
            }
            try {
                recorder.release();
            } catch (Throwable ignored) {
            }
            recorder = null;
        }
    }

    private boolean isMicrophonePermissionGranted() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAutoEnabled() {
        return getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("enabled", false);
    }

    private boolean autoPhoneEnabled() {
        return getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("auto_phone", true);
    }

    private boolean autoVoipEnabled() {
        return getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("auto_voip", true);
    }

    private boolean isVoipSource(String source) {
        return source.startsWith("ZALO")
                || source.startsWith("MESSENGER")
                || source.startsWith("FACEBOOK")
                || source.startsWith("WHATSAPP")
                || source.startsWith("VOIP")
                || source.startsWith("APP_CALL");
    }

    public static boolean isRecording() {
        return recording;
    }

    private String safeSource(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void setLastEvent(String event) {
        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putString("last_event", event)
                .putLong("last_event_time", System.currentTimeMillis())
                .apply();
    }

    private void setLastError(String error) {
        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putString("last_error", error)
                .putLong("last_error_time", System.currentTimeMillis())
                .apply();
    }

    private String shortError(Throwable t) {
        if (t == null) {
            return "không rõ";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + message;
    }

    private String audioModeName(int mode) {
        if (mode == AudioManager.MODE_NORMAL) return "NORMAL";
        if (mode == AudioManager.MODE_RINGTONE) return "RINGTONE";
        if (mode == AudioManager.MODE_IN_CALL) return "IN_CALL";
        if (mode == AudioManager.MODE_IN_COMMUNICATION) return "IN_COMMUNICATION";
        if (mode == AudioManager.MODE_CALL_SCREENING) return "CALL_SCREENING";
        return String.valueOf(mode);
    }

    private void createChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (manager == null || Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                "Tien Call Recorder",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Dịch vụ chờ và tự ghi khi phát hiện cuộc gọi.");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                1,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stop = new Intent(this, MonitorRecorderService.class)
                .setAction(ACTION_DISABLE);
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                2,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("Tien Call Recorder")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat_mic,
                        "Tắt tự động",
                        stopIntent
                ).build())
                .build();
    }

    private void notifyText(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(3001, buildNotification(text));
        }
    }

    private void updateWaitingNotification() {
        if (!recording) {
            notifyText(isAutoEnabled() ? "Đang chờ cuộc gọi" : "Đã dừng tự động");
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(audioModePoller);
        stopRecording("Dịch vụ đã dừng");

        if (Build.VERSION.SDK_INT >= 31
                && telephonyManager != null
                && phoneCallback != null) {
            try {
                telephonyManager.unregisterTelephonyCallback(phoneCallback);
            } catch (Throwable ignored) {
            }
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
