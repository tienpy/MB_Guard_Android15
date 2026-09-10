package com.mrtien.tiencallrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;
    private TextView statusView;
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
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(246,248,252));
        scroll.addView(root);

        TextView title = text("Tien Call Recorder", 28, true);
        title.setTextColor(Color.rgb(13,71,161));
        root.addView(title);

        TextView info = text(
                "Android 15 không cho ứng dụng thường lấy trực tiếp luồng âm thanh cuộc gọi hai chiều. "
                        + "Bản này tự ghi bằng microphone khi phát hiện cuộc gọi. Với Zalo/Messenger/Facebook, "
                        + "khả năng thu được tiếng phía bên kia phụ thuộc điện thoại và thường rõ hơn khi bật loa ngoài.",
                15, false);
        info.setPadding(0, dp(8), 0, dp(12));
        root.addView(info);

        statusView = card(root, "TRẠNG THÁI");

        autoPhone = new CheckBox(this);
        autoPhone.setText("Tự ghi cuộc gọi điện thoại");
        autoPhone.setTextSize(16);
        root.addView(autoPhone);

        autoVoip = new CheckBox(this);
        autoVoip.setText("Tự ghi Zalo / Messenger / Facebook khi phát hiện thông báo cuộc gọi");
        autoVoip.setTextSize(16);
        root.addView(autoVoip);

        speakerMode = new CheckBox(this);
        speakerMode.setText("Tự bật loa ngoài khi bắt đầu ghi (giúp thu hai phía rõ hơn)");
        speakerMode.setTextSize(16);
        root.addView(speakerMode);

        Button save = primary("LƯU + BẬT TỰ ĐỘNG");
        save.setOnClickListener(v -> enableAuto());
        root.addView(save);

        Button stop = button("TẮT TỰ ĐỘNG");
        stop.setOnClickListener(v -> disableAuto());
        root.addView(stop);

        Button notif = button("CẤP QUYỀN ĐỌC THÔNG BÁO (CHO ZALO/MESSENGER)");
        notif.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Không mở được cài đặt thông báo.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(notif);

        Button manual = button("GHI THỬ NGAY");
        manual.setOnClickListener(v -> sendServiceAction(MonitorRecorderService.ACTION_MANUAL_START, "TEST"));
        root.addView(manual);

        Button stopManual = button("DỪNG GHI THỬ");
        stopManual.setOnClickListener(v -> sendServiceAction(MonitorRecorderService.ACTION_MANUAL_STOP, ""));
        root.addView(stopManual);

        folderView = card(root, "NƠI LƯU FILE");

        Button folder = button("MỞ THƯ MỤC GHI ÂM");
        folder.setOnClickListener(v -> openRecordingsFolder());
        root.addView(folder);

        TextView note = text(
                "Để tự động ổn định trên Android 15, sau khi bấm “LƯU + BẬT TỰ ĐỘNG” hãy để thông báo "
                        + "“Tien Call Recorder đang chờ cuộc gọi” luôn tồn tại. Sau khi khởi động lại điện thoại, "
                        + "hãy mở app và bật tự động lại nếu hệ thống đã dừng dịch vụ.",
                14,false);
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void enableAuto() {
        if (!hasCorePermissions()) {
            requestCorePermissions();
            return;
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putBoolean("auto_phone", autoPhone.isChecked())
                .putBoolean("auto_voip", autoVoip.isChecked())
                .putBoolean("speaker", speakerMode.isChecked())
                .putBoolean("enabled", true)
                .apply();
        Intent i = new Intent(this, MonitorRecorderService.class).setAction(MonitorRecorderService.ACTION_ENABLE);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "Đã bật tự động ghi.", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void disableAuto() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        sendServiceAction(MonitorRecorderService.ACTION_DISABLE, "");
        refresh();
    }

    private void sendServiceAction(String action, String source) {
        if (!hasCorePermissions()) {
            requestCorePermissions();
            return;
        }
        Intent i = new Intent(this, MonitorRecorderService.class).setAction(action);
        i.putExtra("source", source);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void refresh() {
        boolean enabled = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("enabled", false);
        autoPhone.setChecked(getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("auto_phone", true));
        autoVoip.setChecked(getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("auto_voip", true));
        speakerMode.setChecked(getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("speaker", false));

        statusView.setText(
                "Microphone: " + (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "ĐÃ CẤP" : "CHƯA CẤP")
                        + "\nPhone state: " + (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ? "ĐÃ CẤP" : "CHƯA CẤP")
                        + "\nTự động: " + (enabled ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nĐang ghi: " + (MonitorRecorderService.isRecording() ? "CÓ" : "KHÔNG")
        );
        statusView.setTextColor(enabled ? Color.rgb(20,110,45) : Color.rgb(150,60,40));

        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "TienCallRecorder");
        folderView.setText(dir.getAbsolutePath());
    }

    private void requestCorePermissions() {
        java.util.ArrayList<String> p = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERMISSIONS);
    }

    private boolean hasCorePermissions() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private void openRecordingsFolder() {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "TienCallRecorder");
        dir.mkdirs();
        Toast.makeText(this, "Thư mục: " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private TextView card(LinearLayout root, String heading) {
        TextView h = text(heading, 17, true);
        h.setPadding(0, dp(12), 0, dp(5));
        root.addView(h);
        TextView v = text("", 15, false);
        v.setBackgroundColor(Color.WHITE);
        v.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(v);
        return v;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(25,25,25));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
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
        b.setBackgroundColor(Color.rgb(21,101,192));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
