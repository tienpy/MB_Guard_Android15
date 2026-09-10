package com.mrtien.tienshakeactions;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final String[] LABELS = {
            "Không làm gì", "Back", "Chụp màn hình", "Home",
            "Recent apps", "Mở thông báo", "Mở cài đặt nhanh"
    };
    private static final String[] VALUES = {
            "NONE", "BACK", "SCREENSHOT", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS"
    };

    private Spinner singleSpinner;
    private Spinner doubleSpinner;
    private SeekBar sensitivity;
    private TextView sensitivityLabel;
    private TextView status;

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
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(246,248,252));
        scroll.addView(root);

        TextView title = text("Tien Shake Actions", 28, true);
        title.setTextColor(Color.rgb(0,105,92));
        root.addView(title);

        TextView intro = text(
                "Lắc điện thoại để thao tác nhanh. Ứng dụng dùng Accessibility để thực hiện Back, "
                        + "Home, Recent, chụp màn hình và mở bảng thông báo. Không đọc nội dung trên màn hình.",
                15, false);
        intro.setPadding(0, dp(8), 0, dp(12));
        root.addView(intro);

        status = card(root, "TRẠNG THÁI");

        Button access = primary("BẬT TRỢ NĂNG CHO TIEN SHAKE ACTIONS");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        addHeading(root, "Lắc 1 lần");
        singleSpinner = spinner();
        root.addView(singleSpinner);

        addHeading(root, "Lắc 2 lần");
        doubleSpinner = spinner();
        root.addView(doubleSpinner);

        addHeading(root, "Độ nhạy");
        sensitivityLabel = text("", 15, false);
        root.addView(sensitivityLabel);
        sensitivity = new SeekBar(this);
        sensitivity.setMax(100);
        sensitivity.setMin(1);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sensitivityLabel.setText("Độ nhạy: " + progress + "/100  (cao hơn = dễ kích hoạt hơn)");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sensitivity);

        Button save = primary("LƯU CÀI ĐẶT");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);

        TextView note = text(
                "Gợi ý ban đầu: Lắc 1 lần = Back, lắc 2 lần = Chụp màn hình, độ nhạy 60. "
                        + "Nếu hay kích hoạt nhầm khi đi xe hoặc cầm điện thoại, giảm độ nhạy xuống 40–50.",
                14,false);
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);
        return scroll;
    }

    private Spinner spinner() {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LABELS);
        s.setAdapter(a);
        return s;
    }

    private void loadSettings() {
        String single = getSharedPreferences("prefs", MODE_PRIVATE).getString("single_action", "BACK");
        String dbl = getSharedPreferences("prefs", MODE_PRIVATE).getString("double_action", "SCREENSHOT");
        int sens = getSharedPreferences("prefs", MODE_PRIVATE).getInt("sensitivity", 60);
        singleSpinner.setSelection(indexOf(single));
        doubleSpinner.setSelection(indexOf(dbl));
        sensitivity.setProgress(sens);
        sensitivityLabel.setText("Độ nhạy: " + sens + "/100  (cao hơn = dễ kích hoạt hơn)");
    }

    private void saveSettings() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("single_action", VALUES[singleSpinner.getSelectedItemPosition()])
                .putString("double_action", VALUES[doubleSpinner.getSelectedItemPosition()])
                .putInt("sensitivity", sensitivity.getProgress())
                .apply();
        Toast.makeText(this, "Đã lưu.", Toast.LENGTH_SHORT).show();
    }

    private int indexOf(String value) {
        for (int i=0;i<VALUES.length;i++) if (VALUES[i].equals(value)) return i;
        return 0;
    }

    private void refreshStatus() {
        boolean on = isServiceEnabled();
        status.setText("Trợ năng Tien Shake Actions: " + (on ? "ĐANG BẬT" : "ĐANG TẮT")
                + "\nCảm biến lắc: " + (on ? "ĐANG HOẠT ĐỘNG" : "CHƯA HOẠT ĐỘNG"));
        status.setTextColor(on ? Color.rgb(20,110,45) : Color.rgb(160,55,35));
    }

    private boolean isServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String mine = getPackageName();
        for (AccessibilityServiceInfo info : list) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                    && mine.equals(info.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }

    private TextView card(LinearLayout root, String heading) {
        addHeading(root, heading);
        TextView t = text("",15,false);
        t.setBackgroundColor(Color.WHITE);
        t.setPadding(dp(14),dp(12),dp(14),dp(12));
        root.addView(t);
        return t;
    }

    private void addHeading(LinearLayout root, String h) {
        TextView t=text(h,17,true);
        t.setPadding(0,dp(12),0,dp(5));
        root.addView(t);
    }

    private Button primary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(0,137,123));
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,dp(52));
        p.topMargin=dp(7);
        b.setLayoutParams(p);
        return b;
    }

    private TextView text(String s,int sp,boolean bold) {
        TextView t=new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(25,25,25));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
}
