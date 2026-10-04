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

    private static final int REQ_VPN = 501;

    private TextView statusView;
    private TextView statsView;
    private CheckBox doubleTapPaste;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("auto_skip", false)
                .putBoolean("close_x", false)
                .apply();
        setContentView(buildUi());
        loadPrefs();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ShizukuClipboardBridge.hasPermission()) {
            ShizukuClipboardBridge.start(this);
        }
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
                "Bản 2.2 giữ nguyên chặn quảng cáo bằng DNS/VPN. Phần DÁN được làm lại để hoạt động trong mọi ô nhập mà Trợ năng nhìn thấy: chỉ lớp cảm ứng của chính ô đang có con trỏ được theo dõi, không đụng vào bàn phím. Nhấp đúp sẽ hiện DÁN + nút lịch sử.",
                16, false);
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
                "Phần tự theo dõi/tự bấm quảng cáo đã bị loại bỏ hoàn toàn. Chỉ còn lớp DNS/VPN vì phần này trên máy anh đang hoạt động tốt.",
                14, false);
        dnsNote.setTextColor(Color.DKGRAY);
        root.addView(dnsNote);

        addHeading(root, "2. NHẤP ĐÚP → DÁN + LỊCH SỬ");
        Button accessibility = primary("BẬT TRỢ NĂNG PASTE SHORTCUT");
        accessibility.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Không mở được cài đặt Trợ năng.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(accessibility);

        doubleTapPaste = new CheckBox(this);
        doubleTapPaste.setText("Nhấp đúp vào ô có con trỏ để hiện DÁN + lịch sử");
        doubleTapPaste.setTextSize(16);
        root.addView(doubleTapPaste);

        TextView pasteNote = text(
                "DÁN sẽ dán ngay nội dung vừa copy. Nút ▤ bên cạnh mở các nội dung đã copy trước đó. Bản này không dùng Touch Exploration nên bàn phím không bị khóa.",
                14, false);
        pasteNote.setTextColor(Color.DKGRAY);
        root.addView(pasteNote);

        addHeading(root, "3. LỊCH SỬ CLIPBOARD TOÀN HỆ THỐNG");
        Button shizuku = primary("CẤP QUYỀN LỊCH SỬ TOÀN HỆ THỐNG (SHIZUKU)");
        shizuku.setOnClickListener(v -> requestShizuku());
        root.addView(shizuku);

        TextView shizukuNote = text(
                "Android 15 không cho ứng dụng thường đọc clipboard của app khác trong nền. Nếu anh muốn lịch sử tự nhớ mọi lần copy ở Zalo/Facebook/trình duyệt…, cần Shizuku chạy bằng Gỡ lỗi không dây. Không cần root. Nếu không dùng Shizuku, nút DÁN mới nhất vẫn hoạt động; lịch sử chỉ lưu được những mục Android cho phép đọc.",
                14, false);
        shizukuNote.setTextColor(Color.rgb(110, 75, 20));
        root.addView(shizukuNote);

        Button save = primary("LƯU CÀI ĐẶT");
        save.setOnClickListener(v -> savePrefs());
        root.addView(save);

        statsView = card(root, "THỐNG KÊ / LỊCH SỬ");

        Button clearHistory = button("XÓA TOÀN BỘ LỊCH SỬ COPY");
        clearHistory.setOnClickListener(v -> {
            ClipboardHistoryStore.clear(this);
            refreshStatus();
            Toast.makeText(this, "Đã xóa lịch sử.", Toast.LENGTH_SHORT).show();
        });
        root.addView(clearHistory);

        Button resetCount = button("XÓA SỐ LẦN DÁN");
        resetCount.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_PASTE_COUNT, 0L).apply();
            refreshStatus();
        });
        root.addView(resetCount);

        TextView important = text(
                "Sau khi cài đè bản 2.2, hãy vào Trợ năng → Paste Shortcut → TẮT rồi BẬT lại một lần để Android nạp cấu hình canPerformGestures mới.",
                14, true);
        important.setTextColor(Color.rgb(150, 80, 15));
        important.setPadding(0, dp(16), 0, 0);
        root.addView(important);

        return scroll;
    }

    private void requestShizuku() {
        if (!ShizukuClipboardBridge.isAvailable()) {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) {
                startActivity(launch);
                Toast.makeText(this, "Hãy khởi động Shizuku bằng Gỡ lỗi không dây rồi quay lại app.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Máy chưa cài Shizuku. Lịch sử chữ vẫn hoạt động ở mức Android cho phép; muốn lịch sử toàn hệ thống cần cài Shizuku.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (ShizukuClipboardBridge.hasPermission()) {
            ShizukuClipboardBridge.start(this);
            Toast.makeText(this, "Lịch sử toàn hệ thống đã sẵn sàng.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        } else {
            ShizukuClipboardBridge.requestPermission();
            Toast.makeText(this, "Chọn Cho phép trong cửa sổ Shizuku.", Toast.LENGTH_LONG).show();
        }
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DNS_BLOCK_WANTED, true).apply();
        Intent service = new Intent(this, DnsAdBlockVpnService.class).setAction(DnsAdBlockVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            Toast.makeText(this, "Đã bật chặn quảng cáo mạng.", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Không khởi động được chặn quảng cáo mạng.", Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    private void stopDnsBlocker() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DNS_BLOCK_WANTED, false).apply();
        Intent service = new Intent(this, DnsAdBlockVpnService.class).setAction(DnsAdBlockVpnService.ACTION_STOP);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        } catch (Throwable ignored) {}
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
                .putBoolean(KEY_DOUBLE_TAP_PASTE, doubleTapPaste.isChecked()).apply();
        Toast.makeText(this, "Đã lưu cài đặt.", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean accessibility = isServiceEnabled();
        boolean dns = DnsAdBlockVpnService.isRunning();
        boolean paste = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_DOUBLE_TAP_PASTE, true);
        boolean shizuku = ShizukuClipboardBridge.hasPermission();
        int historyCount = ClipboardHistoryStore.load(this).size();

        statusView.setText(
                "Chặn quảng cáo mạng: " + (dns ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nTrợ năng Paste Shortcut: " + (accessibility ? "ĐANG BẬT" : "ĐANG TẮT")
                        + "\nNhấp đúp → DÁN: " + (paste ? "BẬT" : "TẮT")
                        + "\nLịch sử toàn hệ thống (Shizuku): " + (shizuku ? "ĐÃ CẤP" : "CHƯA CẤP")
                        + "\nTự theo dõi/bấm quảng cáo: ĐÃ LOẠI BỎ"
        );
        statusView.setTextColor(dns && accessibility ? Color.rgb(20, 120, 65) : Color.rgb(170, 90, 35));

        if (statsView != null) {
            long pasteCount = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_PASTE_COUNT, 0L);
            statsView.setText("Đã dán: " + pasteCount + " lần\nLịch sử đang lưu: " + historyCount + " mục");
        }
    }

    private boolean isServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) return true;
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
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
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
