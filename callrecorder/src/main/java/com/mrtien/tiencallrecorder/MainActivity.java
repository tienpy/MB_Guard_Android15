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

    private TextView statusView;
    private TextView debugView;
    private CheckBox autoHonorSystem;
    private CheckBox autoVoip;

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
                "Bản 1.2 dành cho HONOR Magic V3. Cuộc gọi SIM không còn dùng microphone của app. "
                        + "Khi cuộc gọi được kết nối, Trợ năng sẽ tự tìm và bấm nút Ghi âm có sẵn của HONOR đúng 1 lần. "
                        + "Như vậy phần ghi do hệ thống HONOR thực hiện và phù hợp hơn khi dùng tai nghe/Bluetooth. "
                        + "Zalo/Messenger/Facebook vẫn dùng cơ chế ghi riêng của app khi Android cho phép.",
                15,
                false
        );
        info.setPadding(0, dp(8), 0, dp(12));
        root.addView(info);

        statusView = card(root, "TRẠNG THÁI");

        autoHonorSystem = checkbox("Tự bấm nút Ghi âm của HONOR khi cuộc gọi SIM kết nối");
        autoVoip = checkbox("Tự thử ghi Zalo / Messenger / Facebook / app VoIP");
        root.addView(autoHonorSystem);
        root.addView(autoVoip);

        Button save = primary("LƯU + BẬT TỰ ĐỘNG");
        save.setOnClickListener(v -> enableAuto());
        root.addView(save);

        Button stop = button("TẮT TỰ ĐỘNG");
        stop.setOnClickListener(v -> disableAuto());
        root.addView(stop);

        Button notification = button("CẤP QUYỀN ĐỌC THÔNG BÁO");
        notification.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Không mở được cài đặt quyền thông báo.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(notification);

        Button accessibility = primary("BẬT / KHỞI ĐỘNG LẠI TRỢ NĂNG GHI ÂM HONOR");
        accessibility.setOnClickListener(v -> {
            Toast.makeText(
                    this,
                    "Nếu đang bật sẵn, hãy tắt Tien Call Recorder hỗ trợ ghi âm rồi bật lại một lần.",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        root.addView(accessibility);

        addHeading(root, "THƯ MỤC GHI ÂM");

        TextView honorFolder = text(
                "Cuộc gọi SIM do HONOR ghi: Bộ nhớ trong → Sounds → CallRecord",
                15,
                false
        );
        honorFolder.setBackgroundColor(Color.WHITE);
        honorFolder.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(honorFolder);

        Button openHonor = primary("MỞ THẲNG THƯ MỤC GHI ÂM HONOR");
        openHonor.setOnClickListener(v -> openFolder("Sounds/CallRecord"));
        root.addView(openHonor);

        TextView appFolder = text(
                "File do Tien Call Recorder tự ghi: Bộ nhớ trong → Music → TienCallRecorder",
                15,
                false
        );
        appFolder.setBackgroundColor(Color.WHITE);
        appFolder.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams appFolderParams = new LinearLayout.LayoutParams(-1, -2);
        appFolderParams.topMargin = dp(10);
        appFolder.setLayoutParams(appFolderParams);
        root.addView(appFolder);

        Button openApp = button("MỞ THƯ MỤC FILE APP ĐÃ GHI");
        openApp.setOnClickListener(v -> openFolder("Music/TienCallRecorder"));
        root.addView(openApp);

        Button listAppFiles = button("XEM DANH SÁCH FILE APP ĐÃ GHI");
        listAppFiles.setOnClickListener(v -> showAppRecordings());
        root.addView(listAppFiles);

        addHeading(root, "TEST GHI RIÊNG CỦA APP");
        Button manual = button("GHI THỬ MICROPHONE NGAY");
        manual.setOnClickListener(v -> sendServiceAction(MonitorRecorderService.ACTION_MANUAL_START, "TEST"));
        root.addView(manual);

        Button stopManual = button("DỪNG GHI THỬ");
        stopManual.setOnClickListener(v -> sendServiceAction(MonitorRecorderService.ACTION_MANUAL_STOP, ""));
        root.addView(stopManual);

        debugView = card(root, "CHẨN ĐOÁN");

        Button refreshButton = button("CẬP NHẬT TRẠNG THÁI");
        refreshButton.setOnClickListener(v -> refresh());
        root.addView(refreshButton);

        TextView guide = text(
                "Cách dùng cho HONOR Magic V3:\n"
                        + "1. Tích “Tự bấm nút Ghi âm của HONOR”.\n"
                        + "2. Bấm LƯU + BẬT TỰ ĐỘNG.\n"
                        + "3. Bấm BẬT / KHỞI ĐỘNG LẠI TRỢ NĂNG, tắt rồi bật lại dịch vụ Tien Call Recorder hỗ trợ ghi âm một lần.\n"
                        + "4. Gọi một cuộc SIM bình thường. Khi bên kia bắt máy, app sẽ tìm nút “Ghi âm” trên giao diện cuộc gọi và bấm tự động.\n"
                        + "5. Kết thúc cuộc gọi rồi bấm MỞ THẲNG THƯ MỤC GHI ÂM HONOR.\n\n"
                        + "Nếu không bấm được, chụp màn hình phần CHẨN ĐOÁN và giao diện cuộc gọi có nút Ghi âm để tôi bắt đúng nhãn/nút của MagicOS trên máy anh.",
                14,
                false
        );
        guide.setTextColor(Color.DKGRAY);
        guide.setPadding(0, dp(16), 0, 0);
        root.addView(guide);

        return scroll;
    }

    private void enableAuto() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestCorePermissions();
            return;
        }

        boolean honor = autoHonorSystem.isChecked();
        boolean voip = autoVoip.isChecked();

        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putBoolean("auto_honor_system", honor)
                .putBoolean("auto_phone", false)
                .putBoolean("auto_voip", voip)
                .putBoolean("speaker", false)
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
            Toast.makeText(this, "Đã bật tự động.", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Không khởi động được dịch vụ tự động.", Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void disableAuto() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putBoolean("enabled", false)
                .putBoolean("auto_honor_system", false)
                .apply();
        sendServiceAction(MonitorRecorderService.ACTION_DISABLE, "");
        refresh();
    }

    private void sendServiceAction(String action, String source) {
        Intent intent = new Intent(this, MonitorRecorderService.class).setAction(action);
        intent.putExtra("source", source);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "Không chạy được lệnh ghi.", Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        boolean enabled = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("enabled", false);
        boolean honor = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("auto_honor_system", true);
        boolean voip = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("auto_voip", true);

        autoHonorSystem.setChecked(honor);
        autoVoip.setChecked(voip);

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        boolean notification = isNotificationListenerEnabled();
        boolean accessibility = isAccessibilityHelperEnabled();

        statusView.setText(
                "Phone state: " + yesNo(phone)
                        + "\nTrợ năng HONOR: " + yesNo(accessibility)
                        + "\nTự bấm Ghi âm HONOR: " + (honor ? "BẬT" : "TẮT")
                        + "\nMicrophone cho Zalo/app: " + yesNo(mic)
                        + "\nĐọc thông báo: " + yesNo(notification)
                        + "\nTự động chung: " + (enabled ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nApp đang tự ghi micro: " + (MonitorRecorderService.isRecording() ? "CÓ" : "KHÔNG")
        );
        statusView.setTextColor(enabled && phone && accessibility
                ? Color.rgb(20, 110, 45)
                : Color.rgb(160, 55, 35));

        String honorEvent = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("last_honor_event", "Chưa có sự kiện HONOR");
        long honorTime = getSharedPreferences("prefs", MODE_PRIVATE)
                .getLong("last_honor_event_time", 0L);
        int clickCount = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("honor_auto_click_count", 0);
        String lastEvent = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("last_event", "Chưa có sự kiện ghi riêng");
        String lastError = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("last_error", "");
        int audioMode = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("last_audio_mode", AudioManager.MODE_NORMAL);

        debugView.setText(
                "HONOR gần nhất: " + honorEvent + formatTime(honorTime)
                        + "\nSố lần đã tự bấm Ghi âm HONOR: " + clickCount
                        + "\nAudio mode: " + audioModeName(audioMode)
                        + "\nGhi riêng gần nhất: " + lastEvent
                        + "\nLỗi ghi riêng: " + (lastError.isEmpty() ? "Không có" : lastError)
        );
    }

    private void openFolder(String relativePath) {
        Uri folderUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:" + relativePath
        );
        try {
            Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if (Build.VERSION.SDK_INT >= 26) {
                tree.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri);
            }
            startActivity(tree);
        } catch (Throwable t) {
            Toast.makeText(this, "Không mở được thư mục: " + relativePath, Toast.LENGTH_LONG).show();
        }
    }

    private void showAppRecordings() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Chức năng này cần Android 10 trở lên.", Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<String> names = new ArrayList<>();
        ArrayList<Uri> uris = new ArrayList<>();
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=?";
        String[] args = {Environment.DIRECTORY_MUSIC + "/TienCallRecorder/"};
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME
        };

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    names.add(cursor.getString(nameCol));
                    uris.add(Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id)));
                }
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Không đọc được danh sách file.", Toast.LENGTH_LONG).show();
            return;
        }

        if (names.isEmpty()) {
            Toast.makeText(this, "Chưa có file do app tự ghi.", Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("File Tien Call Recorder")
                .setItems(names.toArray(new String[0]), (dialog, which) -> playAudio(uris.get(which)))
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void playAudio(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "audio/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "Không có ứng dụng phát file âm thanh.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isNotificationListenerEnabled() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return manager != null && manager.isNotificationListenerAccessGranted(
                    new ComponentName(this, CallNotificationListener.class)
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isAccessibilityHelperEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        );
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private void requestCorePermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private String yesNo(boolean value) {
        return value ? "ĐÃ CẤP" : "CHƯA CẤP";
    }

    private String formatTime(long time) {
        if (time <= 0L) return "";
        return " (" + new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(time)) + ")";
    }

    private String audioModeName(int mode) {
        if (mode == AudioManager.MODE_NORMAL) return "NORMAL";
        if (mode == AudioManager.MODE_RINGTONE) return "RINGTONE";
        if (mode == AudioManager.MODE_IN_CALL) return "IN_CALL";
        if (mode == AudioManager.MODE_IN_COMMUNICATION) return "IN_COMMUNICATION";
        if (mode == AudioManager.MODE_CALL_SCREENING) return "CALL_SCREENING";
        return String.valueOf(mode);
    }

    private CheckBox checkbox(String label) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(16);
        return box;
    }

    private TextView card(LinearLayout root, String heading) {
        addHeading(root, heading);
        TextView value = text("", 15, false);
        value.setBackgroundColor(Color.WHITE);
        value.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(value);
        return value;
    }

    private void addHeading(LinearLayout root, String heading) {
        TextView h = text(heading, 17, true);
        h.setPadding(0, dp(14), 0, dp(5));
        root.addView(h);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.topMargin = dp(7);
        b.setLayoutParams(p);
        return b;
    }

    private Button primary(String label) {
        Button b = button(label);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(21, 101, 192));
        return b;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(25, 25, 25));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
