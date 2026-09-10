package com.mrtien.tienshakeactions;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private CheckBox vibrateFeedback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        migrateV201();
        setContentView(buildUi());
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
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
                "Bản 2.0.1 dùng gyroscope và có chế độ HỌC CỬ CHỈ. "
                        + "Anh không cần quan tâm trục X/Y/Z. Hãy dạy app đúng động tác tay của anh một lần.",
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

        addHeading(root, "HỌC + GÁN CỬ CHỈ");

        TextView learnHelp = text(
                "Cách dùng: bấm nút HỌC của cử chỉ → giữ điện thoại yên khoảng nửa giây "
                        + "→ thực hiện đúng động tác một lần. Khi học xong máy sẽ rung dài và báo “Đã học”.",
                14,
                false
        );
        learnHelp.setTextColor(Color.DKGRAY);
        learnHelp.setPadding(0, 0, 0, dp(6));
        root.addView(learnHelp);

        faceUpSpinner = addGestureRow(
                root,
                "Ngửa máy",
                "gesture_face_up",
                "Mặc định: Back"
        );

        faceDownSpinner = addGestureRow(
                root,
                "Cúi máy",
                "gesture_face_down",
                ""
        );

        tiltLeftSpinner = addGestureRow(
                root,
                "Nghiêng trái",
                "gesture_tilt_left",
                ""
        );

        tiltRightSpinner = addGestureRow(
                root,
                "Nghiêng phải",
                "gesture_tilt_right",
                ""
        );

        twistLeftSpinner = addGestureRow(
                root,
                "Xoay trái",
                "gesture_twist_left",
                ""
        );

        twistRightSpinner = addGestureRow(
                root,
                "Xoay phải",
                "gesture_twist_right",
                ""
        );

        addHeading(root, "ĐỘ NHẠY");
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
                                + "  •  nên để 55–65 khi học cử chỉ"
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
                cooldownLabel.setText(
                        "Thời gian nghỉ: " + (progress * 100) + " ms"
                );
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(cooldownBar);

        vibrateFeedback = new CheckBox(this);
        vibrateFeedback.setText("Rung nhẹ khi cử chỉ thực hiện thành công");
        vibrateFeedback.setTextSize(15);
        root.addView(vibrateFeedback);

        Button saveButton = primary("LƯU CÀI ĐẶT");
        saveButton.setOnClickListener(v -> saveSettings());
        root.addView(saveButton);

        lastGestureView = card(root, "CỬ CHỈ GẦN NHẤT / TRẠNG THÁI HỌC");

        Button refreshButton = button("CẬP NHẬT");
        refreshButton.setOnClickListener(v -> refreshStatus());
        root.addView(refreshButton);

        Button resetLearningButton = button("ĐẶT LẠI BẢN ĐỒ CỬ CHỈ");
        resetLearningButton.setOnClickListener(v -> resetGestureMap());
        root.addView(resetLearningButton);

        TextView guide = text(
                "Để sửa đúng lỗi anh vừa gặp:\n"
                        + "1. Giữ Ngửa máy = Back.\n"
                        + "2. Các mục còn lại = Không làm gì.\n"
                        + "3. Bấm HỌC NGỬA MÁY.\n"
                        + "4. Giữ máy yên khoảng nửa giây.\n"
                        + "5. Ngửa điện thoại đúng kiểu anh thường ngửa.\n"
                        + "6. Khi máy rung dài và báo Đã học, thử lại.\n\n"
                        + "Nếu trước đây động tác ngửa bị ghi là “Xoay trái”, bản này sẽ tự học "
                        + "chuyển động đó thành Ngửa máy.",
                14,
                false
        );
        guide.setTextColor(Color.DKGRAY);
        guide.setPadding(0, dp(16), 0, 0);
        root.addView(guide);

        return scroll;
    }

    private Spinner addGestureRow(
            LinearLayout root,
            String label,
            String gestureKey,
            String note
    ) {
        TextView title = text(label, 17, true);
        title.setPadding(0, dp(9), 0, 0);
        root.addView(title);

        if (note != null && !note.isEmpty()) {
            TextView noteView = text(note, 13, false);
            noteView.setTextColor(Color.DKGRAY);
            root.addView(noteView);
        }

        Spinner spinner = actionSpinner();
        root.addView(spinner);

        Button learnButton = button("HỌC " + label.toUpperCase(Locale.getDefault()));
        learnButton.setOnClickListener(v -> startLearning(gestureKey, label));
        root.addView(learnButton);

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

    private void startLearning(String gestureKey, String label) {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                    this,
                    "Hãy bật Trợ năng Tien Gesture Actions trước.",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putString("learn_gesture", gestureKey)
                .putString("last_gesture", "ĐANG HỌC: " + label)
                .putLong("last_gesture_time", System.currentTimeMillis())
                .apply();

        Toast.makeText(
                this,
                "ĐANG HỌC " + label.toUpperCase(Locale.getDefault())
                        + ": giữ yên khoảng nửa giây rồi thực hiện cử chỉ 1 lần.",
                Toast.LENGTH_LONG
        ).show();

        refreshStatus();
    }

    private void resetGestureMap() {
        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .remove("map_face_up")
                .remove("map_face_down")
                .remove("map_tilt_left")
                .remove("map_tilt_right")
                .remove("map_twist_left")
                .remove("map_twist_right")
                .remove("learn_gesture")
                .putString("last_gesture", "Đã đặt lại. Hãy học lại Ngửa máy.")
                .putLong("last_gesture_time", System.currentTimeMillis())
                .apply();

        Toast.makeText(
                this,
                "Đã đặt lại. Hãy bấm HỌC NGỬA MÁY.",
                Toast.LENGTH_LONG
        ).show();

        refreshStatus();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);

        faceUpSpinner.setSelection(indexOfAction(
                prefs.getString("gesture_face_up", "BACK")
        ));
        faceDownSpinner.setSelection(indexOfAction(
                prefs.getString("gesture_face_down", "NONE")
        ));
        tiltLeftSpinner.setSelection(indexOfAction(
                prefs.getString("gesture_tilt_left", "NONE")
        ));
        tiltRightSpinner.setSelection(indexOfAction(
                prefs.getString("gesture_tilt_right", "NONE")
        ));
        twistLeftSpinner.setSelection(indexOfAction(
                prefs.getString("gesture_twist_left", "NONE")
        ));
        twistRightSpinner.setSelection(indexOfAction(
                prefs.getString("gesture_twist_right", "NONE")
        ));

        int sensitivity = prefs.getInt("gyro_sensitivity", 60);
        sensitivityBar.setProgress(sensitivity);
        sensitivityLabel.setText(
                "Độ nhạy: " + sensitivity + "/100"
                        + "  •  nên để 55–65 khi học cử chỉ"
        );

        int cooldown = prefs.getInt("cooldown_ms", 700);
        int cooldownStep = Math.max(
                3,
                Math.min(15, Math.round(cooldown / 100f))
        );

        cooldownBar.setProgress(cooldownStep);
        cooldownLabel.setText(
                "Thời gian nghỉ: " + (cooldownStep * 100) + " ms"
        );

        vibrateFeedback.setChecked(
                prefs.getBoolean("vibrate_feedback", true)
        );
    }

    private void saveSettings() {
        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putString("gesture_face_up", selectedAction(faceUpSpinner))
                .putString("gesture_face_down", selectedAction(faceDownSpinner))
                .putString("gesture_tilt_left", selectedAction(tiltLeftSpinner))
                .putString("gesture_tilt_right", selectedAction(tiltRightSpinner))
                .putString("gesture_twist_left", selectedAction(twistLeftSpinner))
                .putString("gesture_twist_right", selectedAction(twistRightSpinner))
                .putInt("gyro_sensitivity", sensitivityBar.getProgress())
                .putInt("cooldown_ms", cooldownBar.getProgress() * 100)
                .putBoolean("vibrate_feedback", vibrateFeedback.isChecked())
                .apply();

        Toast.makeText(
                this,
                "Đã lưu cài đặt.",
                Toast.LENGTH_SHORT
        ).show();

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

        String learning = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("learn_gesture", "");

        statusView.setText(
                "Trợ năng: " + (serviceOn ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nGyroscope: " + (gyroAvailable ? "CÓ" : "KHÔNG CÓ")
                        + "\nHọc cử chỉ: "
                        + ((learning == null || learning.isEmpty())
                        ? "SẴN SÀNG"
                        : "ĐANG CHỜ CỬ CHỈ")
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
                lastGesture
                        + (timeText.isEmpty() ? "" : "\n" + timeText)
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
                        dp(50)
                );

        params.topMargin = dp(6);
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
