package com.mrtien.autoskip;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    public static final String PREFS = "autoskip_prefs";
    public static final String KEY_AUTO_SKIP = "auto_skip";
    public static final String KEY_CLOSE_X = "close_x";
    public static final String KEY_DOUBLE_TAP_PASTE = "double_tap_paste";
    public static final String KEY_SKIP_COUNT = "skip_count";
    public static final String KEY_PASTE_COUNT = "paste_count";

    private TextView statusView;
    private TextView statsView;
    private CheckBox autoSkip;
    private CheckBox closeX;
    private CheckBox doubleTapPaste;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadPrefs();
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
        root.setBackgroundColor(Color.rgb(247, 249, 252));
        scroll.addView(root);

        TextView title = text("Auto Skip & Paste", 28, true);
        title.setTextColor(Color.rgb(8, 104, 79));
        root.addView(title);

        TextView intro = text(
                "Tự động bấm Bỏ qua / Skip / Đóng quảng cáo, kể cả quảng cáo nối tiếp nhiều lớp. "
                        + "Ngoài ra, nhấp đúp vào ô đang nhập sẽ hiện nút DÁN ngay cạnh ô đó.",
                16,
                false
        );
        intro.setPadding(0, dp(8), 0, dp(14));
        root.addView(intro);

        statusView = card(root, "TRẠNG THÁI");

        Button accessibility = primary("BẬT TRỢ NĂNG CHO AUTO SKIP & PASTE");
        accessibility.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Không mở được cài đặt Trợ năng.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(accessibility);

        addHeading(root, "TỰ ĐỘNG BỎ QUA QUẢNG CÁO");

        autoSkip = new CheckBox(this);
        autoSkip.setText("Tự bấm Bỏ qua / Skip / Đóng quảng cáo");
        autoSkip.setTextSize(16);
        root.addView(autoSkip);

        closeX = new CheckBox(this);
        closeX.setText("Tự bấm nút X / × nhỏ ở góc quảng cáo");
        closeX.setTextSize(16);
        root.addView(closeX);

        TextView adNote = text(
                "Sau khi bấm bỏ qua một quảng cáo, ứng dụng tiếp tục quét thêm vài giây để xử lý quảng cáo lớp 2, lớp 3. "
                        + "Không tự thao tác trong Cài đặt Android, System UI, trình cài APK và bàn phím.",
                14,
                false
        );
        adNote.setTextColor(Color.DKGRAY);
        adNote.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(adNote);

        addHeading(root, "NHẤP ĐÚP → DÁN");

        doubleTapPaste = new CheckBox(this);
        doubleTapPaste.setText("Nhấp đúp vào ô nhập để hiện nút DÁN");
        doubleTapPaste.setTextSize(16);
        root.addView(doubleTapPaste);

        TextView pasteNote = text(
                "Cách dùng: chạm 2 lần liên tiếp vào ô đang có con trỏ nhấp nháy. Nút DÁN sẽ hiện cạnh ô. "
                        + "Bấm DÁN để dán nội dung Clipboard mà không cần nhấn giữ.",
                14,
                false
        );
        pasteNote.setTextColor(Color.DKGRAY);
        pasteNote.setPadding(dp(2), 0, dp(2), dp(10));
        root.addView(pasteNote);

        Button save = primary("LƯU CÀI ĐẶT");
        save.setOnClickListener(v -> savePrefs());
        root.addView(save);

        statsView = card(root, "THỐNG KÊ");

        Button reset = button("XÓA THỐNG KÊ");
        reset.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_SKIP_COUNT, 0L)
                    .putLong(KEY_PASTE_COUNT, 0L)
                    .apply();
            refreshStatus();
        });
        root.addView(reset);

        TextView limit = text(
                "Lưu ý: một số quảng cáo video/WebView tự vẽ toàn bộ giao diện và không cung cấp nút cho Accessibility. "
                        + "Những quảng cáo đó Android không cho ứng dụng này nhìn thấy nút để tự bấm. Các quảng cáo có nút Skip/Đóng/X thông thường sẽ xử lý được.",
                13,
                false
        );
        limit.setTextColor(Color.rgb(110, 75, 20));
        limit.setPadding(0, dp(14), 0, 0);
        root.addView(limit);

        return scroll;
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        autoSkip.setChecked(p.getBoolean(KEY_AUTO_SKIP, true));
        closeX.setChecked(p.getBoolean(KEY_CLOSE_X, true));
        doubleTapPaste.setChecked(p.getBoolean(KEY_DOUBLE_TAP_PASTE, true));
        refreshStatus();
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_SKIP, autoSkip.isChecked())
                .putBoolean(KEY_CLOSE_X, closeX.isChecked())
                .putBoolean(KEY_DOUBLE_TAP_PASTE, doubleTapPaste.isChecked())
                .apply();
        Toast.makeText(this, "Đã lưu cài đặt.", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean enabled = isServiceEnabled();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean skip = p.getBoolean(KEY_AUTO_SKIP, true);
        boolean paste = p.getBoolean(KEY_DOUBLE_TAP_PASTE, true);

        statusView.setText(
                "Trợ năng Auto Skip & Paste: " + (enabled ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nTự bỏ qua quảng cáo: " + (skip ? "BẬT" : "TẮT")
                        + "\nNhấp đúp → DÁN: " + (paste ? "BẬT" : "TẮT")
        );
        statusView.setTextColor(enabled ? Color.rgb(20, 120, 65) : Color.rgb(180, 55, 45));

        if (statsView != null) {
            statsView.setText(
                    "Đã tự bấm quảng cáo: " + p.getLong(KEY_SKIP_COUNT, 0L)
                            + " lần\nĐã dán bằng nút DÁN: " + p.getLong(KEY_PASTE_COUNT, 0L) + " lần"
            );
        }
    }

    private boolean isServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        );
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private TextView card(LinearLayout root, String heading) {
        addHeading(root, heading);
        TextView value = text("", 16, false);
        value.setBackgroundColor(Color.WHITE);
        value.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(value);
        return value;
    }

    private void addHeading(LinearLayout root, String heading) {
        TextView view = text(heading, 18, true);
        view.setPadding(0, dp(14), 0, dp(5));
        root.addView(view);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        p.topMargin = dp(7);
        b.setLayoutParams(p);
        return b;
    }

    private Button primary(String label) {
        Button b = button(label);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(11, 143, 106));
        return b;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(Color.rgb(30, 30, 30));
        if (bold) {
            v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
