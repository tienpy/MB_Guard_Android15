package com.mrtien.tienshakeactions;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String[] ACTION_LABELS = {
            "Không làm gì",
            "Back",
            "Chụp màn hình",
            "Home",
            "Recent apps",
            "Mở thông báo",
            "Mở cài đặt nhanh"
    };

    private static final String[] ACTION_VALUES = {
            "NONE",
            "BACK",
            "SCREENSHOT",
            "HOME",
            "RECENTS",
            "NOTIFICATIONS",
            "QUICK_SETTINGS"
    };

    private TextView statusView;
    private TextView sensitivityLabel;
    private TextView cooldownLabel;
    private TextView lastGestureView;

    private Spinner faceUpSpinner;
    private Spinner faceDownSpinner;
    private Spinner tiltLeftSpinner;
    private Spinner tiltRightSpinner;
    private Spinner twistLeftSpinner;
    private Spinner twistRightSpinner;

    private SeekBar sensitivityBar;
    private SeekBar cooldownBar;

    private CheckBox invertPitch;
    private CheckBox invertRoll;
    private CheckBox invertYaw;
    private CheckBox vibrateFeedback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        scroll.addView(root);

        TextView title = text("Tien Gesture Actions", 28, true);
        title.setTextColor(Color.rgb(0, 105, 92));
        root.addView(title);

        TextView intro = text(
                "Bản 2.0 làm lại từ đầu theo kiểu Micro Gesture: không dùng lắc gia tốc. "
                        + "Ứng dụng chỉ nhận chuyển động XOAY điện thoại bằng gyroscope. "
                        + "Mỗi cử chỉ phải vượt đủ góc và sau đó điện thoại phải đứng yên lại "
                        + "thì mới cho phép cử chỉ kế tiếp.",
                15,
                false
        );
        intro.setPadding(0, dp(8), 0, dp(12));
        root.addView(intro);

        statusView = card(root, "TRẠNG THÁI");

        Button accessibilityButton = primary("BẬT TRỢ NĂNG CHO TIEN GESTURE ACTIONS");
        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
        root.addView(accessibilityButton);

        addHeading(root, "GÁN CỬ CHỈ");

        faceUpSpinner = addGestureRow(root, "Ngửa máy", "Mặc định: Back");
        faceDownSpinner = addGestureRow(root, "Cúi máy", "");
        tiltLeftSpinner = addGestureRow(root, "Nghiêng trái", "");
        tiltRightSpinner = addGestureRow(root, "Nghiêng phải", "");
        twistLeftSpinner = addGestureRow(root, "Xoay trái", "");
        twistRightSpinner = addGestureRow(root, "Xoay phải", "");

        addHeading(root, "ĐỘ NHẠY XOAY");
        sensitivityLabel = text("", 15, false);
        root.addView(sensitivityLabel);

        sensitivityBar = new SeekBar(this);
        sensitivityBar.setMin(1);
        sensitivityBar.setMax(100);
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sensitivityLabel.setText(
                        "Độ nhạy: " + progress + "/100"
                                + "  •  cao hơn = cần xoay ít góc hơn"
                );
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sensitivityBar);

        addHeading(root, "CHỐNG KÍCH HOẠT LIÊN TỤC");
        cooldownLabel = text("", 15, false);
        root.addView(cooldownLabel);

        cooldownBar = new SeekBar(this);
        cooldownBar.setMin(3);
        cooldownBar.setMax(15);
        cooldownBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ms = progress * 100;
                cooldownLabel.setText("Thời gian nghỉ sau mỗi cử chỉ: " + ms + " ms");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(cooldownBar);

        addHeading(root, "ĐẢO CHIỀU NẾU MÁY NHẬN NGƯỢC");
        invertPitch = checkbox("Đảo Ngửa ↔ Cúi");
        invertRoll = checkbox("Đảo Nghiêng trái ↔ phải");
        invertYaw = checkbox("Đảo Xoay trái ↔ phải");
        root.addView(invertPitch);
        root.addView(invertRoll);
        root.addView(invertYaw);

        vibrateFeedback = checkbox("Rung nhẹ khi cử chỉ đã được nhận");
        root.addView(vibrateFeedback);

        Button saveButton = primary("LƯU CÀI ĐẶT");
        saveButton.setOnClickListener(v -> saveSettings());
        root.addView(saveButton);

        lastGestureView = card(root, "CỬ CHỈ GẦN NHẤT");

        Button refreshButton = button("CẬP NHẬT CỬ CHỈ GẦN NHẤT");
        refreshButton.setOnClickListener(v -> refreshStatus());
        root.addView(refreshButton);

        TextView guide = text(
                "Cách test tốt nhất:\n"
                        + "1. Để các mục khác = Không làm gì.\n"
                        + "2. Chỉ để Ngửa máy = Back.\n"
                        + "3. Giữ điện thoại yên khoảng nửa giây.\n"
                        + "4. Ngửa cổ tay một lần rõ ràng khoảng 20–30°.\n"
                        + "5. Giữ yên lại rồi mới thử lần tiếp theo.\n\n"
                        + "Nếu Ngửa lại bị nhận thành Cúi, chỉ cần bật “Đảo Ngửa ↔ Cúi”.",
                14,
                false
        );
        guide.setTextColor(Color.DKGRAY);
        guide.setPadding(0, dp(16), 0, 0);
        root.addView(guide);

        return scroll;
    }

    private Spinner addGestureRow(LinearLayout root, String name, String note) {
        TextView title = text(name, 16, true);
        title.setPadding(0, dp(8), 0, 0);
        root.addView(title);

        if (!note.isEmpty()) {
            TextView n = text(note, 13, false);
            n.setTextColor(Color.DKGRAY);
            root.addView(n);
        }

        Spinner spinner = actionSpinner();
        root.addView(spinner);
        return spinner;
    }

    private Spinner actionSpinner() {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                ACTION_LABELS
        );
        spinner.setAdapter(adapter);
        return spinner;
    }

    private CheckBox checkbox(String label) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(15);
        return box;
    }

    private void loadSettings() {
        faceUpSpinner.setSelection(indexOfAction(
                getSharedPreferences("prefs", MODE_PRIVATE)
                        .getString("gesture_face_up", "BACK")
        ));
        faceDownSpinner.setSelection(indexOfAction(
                getSharedPreferences("prefs", MODE_PRIVATE)
                        .getString("gesture_face_down", "NONE")
        ));
        tiltLeftSpinner.setSelection(indexOfAction(
                getSharedPreferences("prefs", MODE_PRIVATE)
                        .getString("gesture_tilt_left", "NONE")
        ));
        tiltRightSpinner.setSelection(indexOfAction(
                getSharedPreferences("prefs", MODE_PRIVATE)
                        .getString("gesture_tilt_right", "NONE")
        ));
        twistLeftSpinner.setSelection(indexOfAction(
                getSharedPreferences("prefs", MODE_PRIVATE)
                        .getString("gesture_twist_left", "NONE")
        ));
        twistRightSpinner.setSelection(indexOfAction(
                getSharedPreferences("prefs", MODE_PRIVATE)
                        .getString("gesture_twist_right", "NONE")
        ));

        int sensitivity = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("gyro_sensitivity", 60);
        sensitivityBar.setProgress(sensitivity);
        sensitivityLabel.setText(
                "Độ nhạy: " + sensitivity + "/100"
                        + "  •  cao hơn = cần xoay ít góc hơn"
        );

        int cooldown = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("cooldown_ms", 650);
        int step = Math.max(3, Math.min(15, Math.round(cooldown / 100f)));
        cooldownBar.setProgress(step);
        cooldownLabel.setText(
                "Thời gian nghỉ sau mỗi cử chỉ: " + (step * 100) + " ms"
        );

        invertPitch.setChecked(getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("invert_pitch", false));
        invertRoll.setChecked(getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("invert_roll", false));
        invertYaw.setChecked(getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("invert_yaw", false));
        vibrateFeedback.setChecked(getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("vibrate_feedback", true));
    }

    private void saveSettings() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("gesture_face_up", selectedAction(faceUpSpinner))
                .putString("gesture_face_down", selectedAction(faceDownSpinner))
                .putString("gesture_tilt_left", selectedAction(tiltLeftSpinner))
                .putString("gesture_tilt_right", selectedAction(tiltRightSpinner))
                .putString("gesture_twist_left", selectedAction(twistLeftSpinner))
                .putString("gesture_twist_right", selectedAction(twistRightSpinner))
                .putInt("gyro_sensitivity", sensitivityBar.getProgress())
                .putInt("cooldown_ms", cooldownBar.getProgress() * 100)
                .putBoolean("invert_pitch", invertPitch.isChecked())
                .putBoolean("invert_roll", invertRoll.isChecked())
                .putBoolean("invert_yaw", invertYaw.isChecked())
                .putBoolean("vibrate_feedback", vibrateFeedback.isChecked())
                .apply();

        Toast.makeText(this, "Đã lưu cài đặt cử chỉ.", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private String selectedAction(Spinner spinner) {
        int index = spinner.getSelectedItemPosition();
        if (index < 0 || index >= ACTION_VALUES.length) {
            return "NONE";
        }
        return ACTION_VALUES[index];
    }

    private int indexOfAction(String value) {
        for (int i = 0; i < ACTION_VALUES.length; i++) {
            if (ACTION_VALUES[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private void refreshStatus() {
        boolean serviceOn = isAccessibilityServiceEnabled();

        SensorManager sensorManager =
                (SensorManager) getSystemService(SENSOR_SERVICE);
        boolean gyroAvailable = sensorManager != null
                && sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;

        statusView.setText(
                "Trợ năng: " + (serviceOn ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nGyroscope: " + (gyroAvailable ? "CÓ" : "KHÔNG CÓ")
                        + "\nChế độ nhận diện: XOAY THEO TRỤC X / Y / Z"
        );

        statusView.setTextColor(
                serviceOn && gyroAvailable
                        ? Color.rgb(20, 110, 45)
                        : Color.rgb(160, 55, 35)
        );

        String lastGesture = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("last_gesture", "Chưa nhận cử chỉ nào");
        long lastTime = getSharedPreferences("prefs", MODE_PRIVATE)
                .getLong("last_gesture_time", 0L);

        String timeText = "";
        if (lastTime > 0L) {
            timeText = new SimpleDateFormat(
                    "HH:mm:ss dd/MM/yyyy",
                    Locale.getDefault()
            ).format(new Date(lastTime));
        }

        lastGestureView.setText(
                lastGesture + (timeText.isEmpty() ? "" : "\n" + timeText)
        );
    }

    private boolean isAccessibilityServiceEnabled() {
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

    private TextView card(LinearLayout root, String heading) {
        addHeading(root, heading);
        TextView value = text("", 15, false);
        value.setBackgroundColor(Color.WHITE);
        value.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(value);
        return value;
    }

    private void addHeading(LinearLayout root, String heading) {
        TextView view = text(heading, 17, true);
        view.setPadding(0, dp(14), 0, dp(5));
        root.addView(view);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52)
                );
        params.topMargin = dp(7);
        button.setLayoutParams(params);
        return button;
    }

    private Button primary(String label) {
        Button button = button(label);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(0, 137, 123));
        return button;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(25, 25, 25));
        if (bold) {
            view.setTypeface(
                    view.getTypeface(),
                    android.graphics.Typeface.BOLD
            );
        }
        return view;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }
}
