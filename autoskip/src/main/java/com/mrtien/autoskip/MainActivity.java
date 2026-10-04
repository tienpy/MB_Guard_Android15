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
    public static final String KEY_DOUBLE_TAP_PASTE = "double_tap_paste";
    public static final String KEY_PASTE_COUNT = "paste_count";
    public static final String KEY_DNS_BLOCK_WANTED = "dns_block_wanted";

    // Kept only so upgrades from old versions automatically disable the old feature.
    private static final String LEGACY_AUTO_SKIP = "auto_skip";
    private static final String LEGACY_CLOSE_X = "close_x";

    private static final int REQ_VPN = 501;

    private TextView statusView;
    private TextView statsView;
    private CheckBox doubleTapPaste;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v2.1 permanently removes Accessibility auto-clicking of ads.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(LEGACY_AUTO_SKIP, false)
                .putBoolean(LEGACY_CLOSE_X, false)
                .apply();

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

        TextView title = text("AdBlock & Paste", 28, true);
        title.setTextColor(Color.rgb(8, 104, 79));
        root.addView(title);

        TextView intro = text(
                "Bản 2.1 giữ phần chặn quảng cáo bằng DNS/VPN đang hoạt động tốt và bỏ hoàn toàn cơ chế Accessibility tự tìm/bấm quảng cáo. "
                        + "Phần DÁN được làm lại để không chặn cảm ứng, không khóa bàn phím và không làm màn hình nhảy lung tung.",
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
                "Giữ nguyên cơ chế DNS/VPN của bản trước. Quảng cáo bị chặn trước khi tải nên không cần Accessibility theo dõi hoặc tự bấm nút quảng cáo nữa. "
                        + "Android chỉ cho một VPN hoạt động tại một thời điểm.",
                14,
                false
        );
        dnsNote.setTextColor(Color.DKGRAY);
        dnsNote.setPadding(dp(2), dp(4), dp(2), dp(10));
        root.addView(dnsNote);

        addHeading(root, "2. NHẤP ĐÚP → DÁN");

        Button accessibility = primary("BẬT TRỢ NĂNG CHỈ CHO NÚT DÁN");
        accessibility.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Không mở được cài đặt Trợ năng.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(accessibility);

        doubleTapPaste = new CheckBox(this);
        doubleTapPaste.setText("Nhấp đúp vào ô nhập để hiện nút DÁN");
        doubleTapPaste.setTextSize(16);
        root.addView(doubleTapPaste);

        TextView pasteNote = text(
                "Cơ chế mới KHÔNG bắt trực tiếp cảm ứng và KHÔNG dùng Touch Exploration. Bàn phím, cuộn màn hình và mọi nút khác hoạt động bình thường. "
                        + "Khi Android xác nhận nhấp đúp trong cùng ô nhập hoặc nhấp đúp chọn một từ, nút DÁN nhỏ sẽ hiện phía trên ô trong vài giây, giống menu chỉnh sửa trên iPhone.",
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

        Button reset = button("XÓA THỐNG KÊ DÁN");
        reset.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_PASTE_COUNT, 0L)
                    .apply();
            refreshStatus();
        });
        root.addView(reset);

        TextView note = text(
                "Sau khi cài bản 2.1, hãy TẮT rồi BẬT lại dịch vụ Trợ năng một lần. Việc này rất quan trọng để Android bỏ cấu hình Touch Exploration của bản 2.0.1.",
                14,
                true
        );
        note.setTextColor(Color.rgb(150, 80, 15));
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void requestVpnAndStart() {
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) startActivityForResult(prepare, REQ_VPN);
            else startDnsBlocker();
        } catch (Throwable t) {
            Toast.makeText(this, "Không mở được quyền VPN.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startDnsBlocker();
            else Toast.makeText(this, "Chưa cho phép VPN nên chưa thể chặn quảng cáo mạng.", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Đã bật chặn quảng cáo mạng.", Toast.LENGTH_SHORT).show();
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
        doubleTapPaste.setChecked(p.getBoolean(KEY_DOUBLE_TAP_PASTE, true));
        refreshStatus();
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_DOUBLE_TAP_PASTE, doubleTapPaste.isChecked())
                .apply();
        Toast.makeText(this, "Đã lưu cài đặt.", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean accessibilityEnabled = isServiceEnabled();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean paste = p.getBoolean(KEY_DOUBLE_TAP_PASTE, true);
        boolean dns = DnsAdBlockVpnService.isRunning();

        statusView.setText(
                "Chặn quảng cáo mạng: " + (dns ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nTrợ năng nút DÁN: " + (accessibilityEnabled ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nNhấp đúp → DÁN: " + (paste ? "BẬT" : "TẮT")
                        + "\nTự theo dõi/bấm quảng cáo: ĐÃ LOẠI BỎ"
        );
        statusView.setTextColor(
                dns && accessibilityEnabled
                        ? Color.rgb(20, 120, 65)
                        : Color.rgb(170, 90, 35)
        );

        if (statsView != null) {
            statsView.setText("Đã dán bằng nút DÁN: " + p.getLong(KEY_PASTE_COUNT, 0L) + " lần");
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
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
