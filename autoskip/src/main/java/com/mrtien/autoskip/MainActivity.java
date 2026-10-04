package com.mrtien.autoskip;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
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
    public static final String KEY_DNS_BLOCK_WANTED = "dns_block_wanted";

    private static final int REQ_VPN = 501;

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
        requestNotificationPermissionIfNeeded();
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
                "Bản 2.0 dùng 2 lớp chống quảng cáo: chặn ngay từ mạng bằng DNS/VPN, "
                        + "sau đó Accessibility chỉ xử lý những nút Bỏ qua/X còn sót lại. "
                        + "Nhấp đúp trong ô nhập sẽ hiện nút DÁN.",
                16,
                false
        );
        intro.setPadding(0, dp(8), 0, dp(14));
        root.addView(intro);

        statusView = card(root, "TRẠNG THÁI");

        addHeading(root, "1. CHẶN QUẢNG CÁO TỪ MẠNG");

        Button startDns = primary("BẬT CHẶN QUẢNG CÁO MẠNG");
        startDns.setOnClickListener(v -> requestVpnAndStart());
        root.addView(startDns);

        Button stopDns = button("TẮT CHẶN QUẢNG CÁO MẠNG");
        stopDns.setOnClickListener(v -> stopDnsBlocker());
        root.addView(stopDns);

        TextView dnsNote = text(
                "Đây là lớp mạnh nhất: quảng cáo từ các tên miền quảng cáo bị chặn trước khi tải nên nhiều app sẽ mở thẳng vào nội dung. "
                        + "Android sẽ hỏi cho phép VPN một lần. VPN này chỉ chuyển tiếp DNS tới AdGuard DNS; không chuyển toàn bộ dữ liệu của anh. "
                        + "Không thể chạy cùng lúc với một VPN khác.",
                14,
                false
        );
        dnsNote.setTextColor(Color.DKGRAY);
        dnsNote.setPadding(dp(2), dp(4), dp(2), dp(10));
        root.addView(dnsNote);

        addHeading(root, "2. TRỢ NĂNG XỬ LÝ QUẢNG CÁO CÒN SÓT + DÁN");

        Button accessibility = primary("BẬT TRỢ NĂNG CHO AUTO SKIP & PASTE");
        accessibility.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Không mở được cài đặt Trợ năng.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(accessibility);

        autoSkip = new CheckBox(this);
        autoSkip.setText("Tự bấm Bỏ qua / Skip / Continue to app");
        autoSkip.setTextSize(16);
        root.addView(autoSkip);

        closeX = new CheckBox(this);
        closeX.setText("Tự bấm nút X / × khi Accessibility nhìn thấy nút thật");
        closeX.setTextSize(16);
        root.addView(closeX);

        TextView adNote = text(
                "Bản này KHÔNG còn chạm mù vào góc màn hình nên không tự mở File Manager/Home nữa. "
                        + "Nếu quảng cáo có nút thật, app sẽ bấm; nếu quảng cáo bị chặn từ DNS thì nó không tải ngay từ đầu.",
                14,
                false
        );
        adNote.setTextColor(Color.DKGRAY);
        adNote.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(adNote);

        addHeading(root, "3. NHẤP ĐÚP → DÁN");

        doubleTapPaste = new CheckBox(this);
        doubleTapPaste.setText("Nhấp đúp vào ô đang nhập để hiện DÁN");
        doubleTapPaste.setTextSize(16);
        root.addView(doubleTapPaste);

        TextView pasteNote = text(
                "Bản 2.0 bắt trực tiếp 2 lần chạm trên Android 15 nhưng vẫn chuyển cú chạm xuống ứng dụng, nên gõ bàn phím bình thường sẽ không làm hiện DÁN. "
                        + "Nếu app không gửi được sự kiện chạm, khi anh nhấp đúp chọn một từ thì DÁN cũng sẽ hiện.",
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
                "Không có phương án không-root nào chặn được 100% mọi quảng cáo. DNS/VPN chặn được phần lớn quảng cáo tải từ tên miền riêng; "
                        + "quảng cáo nhúng chung máy chủ với nội dung, quảng cáo đã cache sẵn hoặc app tự dùng DNS mã hóa có thể còn. Accessibility là lớp dự phòng cho các trường hợp đó.",
                13,
                false
        );
        limit.setTextColor(Color.rgb(110, 75, 20));
        limit.setPadding(0, dp(14), 0, 0);
        root.addView(limit);

        return scroll;
    }

    private void requestVpnAndStart() {
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, REQ_VPN);
            } else {
                startDnsBlocker();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Không mở được quyền VPN.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startDnsBlocker();
            } else {
                Toast.makeText(this, "Anh chưa cho phép VPN nên chưa thể chặn quảng cáo từ mạng.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startDnsBlocker() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_DNS_BLOCK_WANTED, true)
                .apply();
        Intent service = new Intent(this, DnsAdBlockVpnService.class)
                .setAction(DnsAdBlockVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "Đã bật chặn quảng cáo từ mạng.", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Không khởi động được chặn quảng cáo mạng.", Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    private void stopDnsBlocker() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_DNS_BLOCK_WANTED, false)
                .apply();
        Intent service = new Intent(this, DnsAdBlockVpnService.class)
                .setAction(DnsAdBlockVpnService.ACTION_STOP);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Throwable ignored) {
        }
        refreshStatus();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 502);
        }
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
        boolean accessibilityEnabled = isServiceEnabled();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean skip = p.getBoolean(KEY_AUTO_SKIP, true);
        boolean paste = p.getBoolean(KEY_DOUBLE_TAP_PASTE, true);
        boolean dns = DnsAdBlockVpnService.isRunning();

        statusView.setText(
                "Chặn quảng cáo mạng: " + (dns ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nTrợ năng Auto Skip & Paste: " + (accessibilityEnabled ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nTự bỏ qua quảng cáo còn sót: " + (skip ? "BẬT" : "TẮT")
                        + "\nNhấp đúp → DÁN: " + (paste ? "BẬT" : "TẮT")
        );
        statusView.setTextColor(
                dns && accessibilityEnabled
                        ? Color.rgb(20, 120, 65)
                        : Color.rgb(170, 90, 35)
        );

        if (statsView != null) {
            statsView.setText(
                    "Đã tự bấm quảng cáo còn sót: " + p.getLong(KEY_SKIP_COUNT, 0L)
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
