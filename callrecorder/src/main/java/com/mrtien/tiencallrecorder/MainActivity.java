package com.mrtien.tiencallrecorder;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;
    private static final String PUBLIC_FOLDER = "Music/TienCallRecorder";

    private TextView statusView;
    private TextView debugView;
    private TextView folderView;
    private CheckBox autoPhone;
    private CheckBox autoVoip;
    private CheckBox speakerMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestCorePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        scroll.addView(root);

        TextView title = text("Tien Call Recorder", 28, true);
        title.setTextColor(Color.rgb(13, 71, 161));
        root.addView(title);

        TextView info = text(
                "Bản 1.1 phát hiện cuộc gọi bằng 3 đường: trạng thái cuộc gọi điện thoại, "
                        + "thông báo của ứng dụng và AudioManager IN_CALL / IN_COMMUNICATION. "
                        + "File ghi xong được lưu công khai ở Bộ nhớ trong → Music → TienCallRecorder.",
                15,
                false
        );
        info.setPadding(0, dp(8), 0, dp(12));
        root.addView(info);

        statusView = card(root, "TRẠNG THÁI");

        autoPhone = checkbox("Tự ghi cuộc gọi điện thoại");
        autoVoip = checkbox("Tự ghi cuộc gọi ứng dụng: Zalo / Messenger / Facebook / app VoIP");
        speakerMode = checkbox("Tự bật loa ngoài khi ghi (có thể giúp thu phía bên kia rõ hơn)");
        root.addView(autoPhone);
        root.addView(autoVoip);
        root.addView(speakerMode);

        Button enable = primary("LƯU + BẬT TỰ ĐỘNG");
        enable.setOnClickListener(v -> enableAuto());
        root.addView(enable);

        Button disable = button("TẮT TỰ ĐỘNG");
        disable.setOnClickListener(v -> disableAuto());
        root.addView(disable);

        Button notificationAccess = button("CẤP QUYỀN ĐỌC THÔNG BÁO");
        notificationAccess.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Không mở được cài đặt quyền thông báo.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(notificationAccess);

        Button accessibility = button("BẬT TRỢ NĂNG HỖ TRỢ GHI ÂM");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
        root.addView(accessibility);

        Button manual = primary("GHI THỬ NGAY");
        manual.setOnClickListener(v ->
                sendServiceAction(MonitorRecorderService.ACTION_MANUAL_START, "TEST")
        );
        root.addView(manual);

        Button stopManual = button("DỪNG GHI THỬ");
        stopManual.setOnClickListener(v ->
                sendServiceAction(MonitorRecorderService.ACTION_MANUAL_STOP, "")
        );
        root.addView(stopManual);

        folderView = card(root, "NƠI LƯU FILE");

        Button openFolder = primary("MỞ THẲNG THƯ MỤC FILE ĐÃ GHI");
        openFolder.setOnClickListener(v -> openRecordingsFolder());
        root.addView(openFolder);

        Button listFiles = button("XEM DANH SÁCH FILE ĐÃ GHI");
        listFiles.setOnClickListener(v -> showRecordingsList());
        root.addView(listFiles);

        debugView = card(root, "CHẨN ĐOÁN TỰ ĐỘNG");

        Button refresh = button("CẬP NHẬT TRẠNG THÁI");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        TextView guide = text(
                "Để kiểm tra nhanh:\n"
                        + "1. Cấp Microphone + Phone state.\n"
                        + "2. Bật quyền đọc thông báo.\n"
                        + "3. Bật Trợ năng hỗ trợ ghi âm.\n"
                        + "4. Tích 2 mục tự ghi và bấm LƯU + BẬT TỰ ĐỘNG.\n"
                        + "5. Phải thấy thông báo “Tien Call Recorder - Đang chờ cuộc gọi”.\n"
                        + "6. Gọi thử. Trong lúc ghi, thông báo đổi thành “ĐANG GHI”.\n"
                        + "7. Kết thúc cuộc gọi rồi bấm MỞ THẲNG THƯ MỤC FILE ĐÃ GHI.",
                14,
                false
        );
        guide.setTextColor(Color.DKGRAY);
        guide.setPadding(0, dp(16), 0, 0);
        root.addView(guide);

        return scroll;
    }

    private CheckBox checkbox(String label) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(16);
        return box;
    }

    private void enableAuto() {
        if (!hasCorePermissions()) {
            requestCorePermissions();
            return;
        }

        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("auto_phone", autoPhone.isChecked())
                .putBoolean("auto_voip", autoVoip.isChecked())
                .putBoolean("speaker", speakerMode.isChecked())
                .putBoolean("enabled", true)
                .putString("last_error", "")
                .apply();

        Intent intent = new Intent(this, MonitorRecorderService.class)
                .setAction(MonitorRecorderService.ACTION_ENABLE);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Đã bật tự động ghi.", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .putString("last_error", "Không khởi động được dịch vụ: " + t.getClass().getSimpleName())
                    .apply();
            Toast.makeText(this, "Không khởi động được dịch vụ tự động.", Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void disableAuto() {
        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("enabled", false)
                .apply();
        sendServiceAction(MonitorRecorderService.ACTION_DISABLE, "");
        refresh();
    }

    private void sendServiceAction(String action, String source) {
        if (!hasCorePermissions()) {
            requestCorePermissions();
            return;
        }

        Intent intent = new Intent(this, MonitorRecorderService.class)
                .setAction(action);
        intent.putExtra("source", source);

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            Toast.makeText(
                    this,
                    "Không chạy được lệnh ghi: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void refresh() {
        boolean enabled = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("enabled", false);

        autoPhone.setChecked(getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("auto_phone", true));
        autoVoip.setChecked(getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("auto_voip", true));
        speakerMode.setChecked(getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("speaker", false));

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        boolean notification = isNotificationListenerEnabled();
        boolean accessibility = isAccessibilityHelperEnabled();

        int count = countRecordings();

        statusView.setText(
                "Microphone: " + yesNo(mic)
                        + "\nPhone state: " + yesNo(phone)
                        + "\nĐọc thông báo: " + yesNo(notification)
                        + "\nTrợ năng hỗ trợ ghi: " + yesNo(accessibility)
                        + "\nTự động: " + (enabled ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nĐang ghi: " + (MonitorRecorderService.isRecording() ? "CÓ" : "KHÔNG")
                        + "\nSố file đã ghi: " + count
        );

        statusView.setTextColor(
                enabled && mic && phone
                        ? Color.rgb(20, 110, 45)
                        : Color.rgb(150, 60, 40)
        );

        folderView.setText(
                "Bộ nhớ trong → Music → TienCallRecorder"
                        + "\n/" + PUBLIC_FOLDER
                        + "\nFile mới: M4A"
        );

        String lastEvent = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("last_event", "Chưa có sự kiện");
        long lastEventTime = getSharedPreferences("prefs", MODE_PRIVATE)
                .getLong("last_event_time", 0L);
        String lastError = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("last_error", "");
        String lastFile = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("last_file", "");
        int audioMode = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("last_audio_mode", AudioManager.MODE_NORMAL);

        debugView.setText(
                "Audio mode gần nhất: " + audioModeName(audioMode)
                        + "\nSự kiện gần nhất: " + lastEvent
                        + formatTime(lastEventTime)
                        + "\nFile gần nhất: " + (lastFile.isEmpty() ? "Chưa có" : lastFile)
                        + "\nLỗi gần nhất: " + (lastError.isEmpty() ? "Không có" : lastError)
        );

        debugView.setTextColor(
                lastError.isEmpty()
                        ? Color.rgb(20, 110, 45)
                        : Color.rgb(170, 60, 35)
        );
    }

    private String yesNo(boolean value) {
        return value ? "ĐÃ CẤP" : "CHƯA CẤP";
    }

    private String formatTime(long time) {
        if (time <= 0L) {
            return "";
        }
        return " (" + new SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
        ).format(new Date(time)) + ")";
    }

    private String audioModeName(int mode) {
        if (mode == AudioManager.MODE_NORMAL) return "NORMAL";
        if (mode == AudioManager.MODE_RINGTONE) return "RINGTONE";
        if (mode == AudioManager.MODE_IN_CALL) return "IN_CALL";
        if (mode == AudioManager.MODE_IN_COMMUNICATION) return "IN_COMMUNICATION";
        if (mode == AudioManager.MODE_CALL_SCREENING) return "CALL_SCREENING";
        return String.valueOf(mode);
    }

    private boolean isNotificationListenerEnabled() {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return false;
            }
            return manager.isNotificationListenerAccessGranted(
                    new ComponentName(this, CallNotificationListener.class)
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isAccessibilityHelperEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }

        List<AccessibilityServiceInfo> services =
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                );

        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(
                    info.getResolveInfo().serviceInfo.packageName
            )) {
                return true;
            }
        }
        return false;
    }

    private void requestCorePermissions() {
        ArrayList<String> permissions = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!permissions.isEmpty()) {
            requestPermissions(
                    permissions.toArray(new String[0]),
                    REQ_PERMISSIONS
            );
        }
    }

    private boolean hasCorePermissions() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openRecordingsFolder() {
        Uri folderUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Music/TienCallRecorder"
        );

        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(folderUri, "vnd.android.document/directory");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
            return;
        } catch (Throwable ignored) {
        }

        try {
            Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if (Build.VERSION.SDK_INT >= 26) {
                tree.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri);
            }
            startActivity(tree);
        } catch (Throwable t) {
            Toast.makeText(
                    this,
                    "Không mở được ứng dụng quản lý tệp. Thư mục là Music/TienCallRecorder.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private int countRecordings() {
        if (Build.VERSION.SDK_INT < 29) {
            return 0;
        }

        int count = 0;
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=?";
        String[] args = {Environment.DIRECTORY_MUSIC + "/TienCallRecorder/"};

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Media._ID},
                selection,
                args,
                null
        )) {
            if (cursor != null) {
                count = cursor.getCount();
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private void showRecordingsList() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Danh sách MediaStore chỉ dùng trên Android 10+.", Toast.LENGTH_LONG).show();
            return;
        }

        String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=?";
        String[] args = {Environment.DIRECTORY_MUSIC + "/TienCallRecorder/"};

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Uri> uris = new ArrayList<>();

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.DURATION
                },
                selection,
                args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    long dateSeconds = cursor.getLong(dateCol);
                    long durationMs = cursor.getLong(durationCol);

                    Uri uri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id)
                    );

                    String date = new SimpleDateFormat(
                            "dd/MM/yyyy HH:mm",
                            Locale.getDefault()
                    ).format(new Date(dateSeconds * 1000L));

                    long totalSeconds = Math.max(0L, durationMs / 1000L);
                    String duration = String.format(
                            Locale.getDefault(),
                            "%02d:%02d",
                            totalSeconds / 60L,
                            totalSeconds % 60L
                    );

                    labels.add(name + "\n" + date + "  •  " + duration);
                    uris.add(uri);
                }
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Không đọc được danh sách file.", Toast.LENGTH_LONG).show();
            return;
        }

        if (labels.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Chưa có file ghi âm")
                    .setMessage("Chưa tìm thấy file nào trong Music/TienCallRecorder.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Các file đã ghi")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    try {
                        Intent play = new Intent(Intent.ACTION_VIEW);
                        play.setDataAndType(uris.get(which), "audio/mp4");
                        play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(play);
                    } catch (Throwable t) {
                        Toast.makeText(this, "Không có ứng dụng mở file âm thanh.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private TextView card(LinearLayout root, String heading) {
        TextView headingView = text(heading, 17, true);
        headingView.setPadding(0, dp(12), 0, dp(5));
        root.addView(headingView);

        TextView value = text("", 15, false);
        value.setBackgroundColor(Color.WHITE);
        value.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(value);
        return value;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(25, 25, 25));
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(52));
        params.topMargin = dp(7);
        button.setLayoutParams(params);
        return button;
    }

    private Button primary(String label) {
        Button button = button(label);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(21, 101, 192));
        return button;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }
}
