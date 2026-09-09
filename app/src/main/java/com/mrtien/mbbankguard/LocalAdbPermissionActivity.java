package com.mrtien.mbbankguard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.adb.AdbStream;

/**
 * Grants WRITE_SECURE_SETTINGS without a PC by pairing this app with Android's
 * own Wireless Debugging daemon. The user still explicitly enables Wireless
 * Debugging and supplies Android's one-time pairing code.
 */
public class LocalAdbPermissionActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private EditText pairingPortView;
    private EditText pairingCodeView;
    private EditText adbPortView;
    private Button grantButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View createContentView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("CẤP QUYỀN TRÊN ĐIỆN THOẠI", 25, true);
        title.setTextColor(Color.rgb(7, 59, 140));
        root.addView(title);

        TextView intro = text(
                "Dành cho Android 11 trở lên (máy của bạn Android 15). "
                        + "MB Guard sẽ dùng Gỡ lỗi không dây của chính điện thoại để tự chạy lệnh cấp "
                        + "WRITE_SECURE_SETTINGS. Không cần cáp USB và không cần máy tính.",
                16,
                false
        );
        intro.setPadding(0, dp(8), 0, dp(12));
        root.addView(intro);

        statusView = text("Đang kiểm tra…", 16, true);
        statusView.setPadding(dp(14), dp(13), dp(14), dp(13));
        statusView.setBackgroundColor(Color.WHITE);
        root.addView(statusView, fullWidthWrap(dp(8)));

        TextView steps = text(
                "LẦN ĐẦU LÀM NHƯ SAU:\n"
                        + "1. Bấm “MỞ GỠ LỖI KHÔNG DÂY”.\n"
                        + "2. Bật “Gỡ lỗi không dây / Wireless debugging”.\n"
                        + "3. Bấm “Ghép nối thiết bị bằng mã ghép nối”.\n"
                        + "4. Giữ màn hình mã ghép nối đang mở. Tốt nhất dùng chia đôi màn hình để mở MB Guard cùng lúc.\n"
                        + "5. Nhập CỔNG GHÉP NỐI và MÃ 6 SỐ bên dưới.\n"
                        + "6. Cổng kết nối ADB có thể để trống để app tự tìm. Nếu tự tìm không được, nhập cổng ở dòng “Địa chỉ IP & cổng” trên trang Gỡ lỗi không dây.\n"
                        + "7. Bấm “GHÉP NỐI + CẤP QUYỀN”.",
                15,
                false
        );
        steps.setPadding(0, dp(8), 0, dp(8));
        root.addView(steps);

        Button openWireless = button("MỞ GỠ LỖI KHÔNG DÂY");
        openWireless.setOnClickListener(v -> openWirelessDebuggingSettings());
        root.addView(openWireless);

        pairingPortView = numberField("Cổng ghép nối, ví dụ 37123");
        root.addView(pairingPortView, fullWidthWrap(dp(4)));

        pairingCodeView = numberField("Mã ghép nối 6 số");
        root.addView(pairingCodeView, fullWidthWrap(dp(4)));

        adbPortView = numberField("Cổng kết nối ADB (không bắt buộc)");
        root.addView(adbPortView, fullWidthWrap(dp(6)));

        grantButton = primaryButton("GHÉP NỐI + CẤP QUYỀN");
        grantButton.setOnClickListener(v -> startGrantFlow());
        root.addView(grantButton);

        Button reconnectButton = button("ĐÃ GHÉP NỐI TRƯỚC ĐÂY – CẤP LẠI");
        reconnectButton.setOnClickListener(v -> {
            pairingPortView.setText("");
            pairingCodeView.setText("");
            startGrantFlow();
        });
        root.addView(reconnectButton);

        TextView note = text(
                "Sau khi trạng thái chuyển thành “ĐÃ CẤP”, bạn có thể tắt Gỡ lỗi không dây. "
                        + "Các chức năng “MỞ APP AN TOÀN” và “BẬT TẮT TRỢ NĂNG” vẫn dùng bình thường, không cần PC.\n\n"
                        + "MB Guard chỉ dùng kết nối ADB nội bộ để cấp quyền WRITE_SECURE_SETTINGS cho chính MB Guard; "
                        + "không chạy lệnh ADB khác.",
                14,
                false
        );
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void startGrantFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showStatus("Điện thoại phải từ Android 11 trở lên để dùng cách này.", true);
            return;
        }
        if (hasSecureSettingsPermission()) {
            showStatus("WRITE_SECURE_SETTINGS: ĐÃ CẤP. Không cần làm lại.", false);
            return;
        }

        String pairPortText = pairingPortView.getText().toString().trim();
        String code = pairingCodeView.getText().toString().trim();
        String adbPortText = adbPortView.getText().toString().trim();

        if (pairPortText.isEmpty() != code.isEmpty()) {
            showStatus("Hãy nhập đủ Cổng ghép nối và Mã ghép nối 6 số, hoặc để trống cả hai nếu đã ghép nối trước đây.", true);
            return;
        }
        if (!code.isEmpty() && (code.length() != 6 || !isDigits(code))) {
            showStatus("Mã ghép nối phải gồm đúng 6 chữ số.", true);
            return;
        }

        final int pairPort;
        final int adbPort;
        try {
            pairPort = pairPortText.isEmpty() ? -1 : parsePort(pairPortText);
            adbPort = adbPortText.isEmpty() ? -1 : parsePort(adbPortText);
        } catch (IllegalArgumentException exception) {
            showStatus(exception.getMessage(), true);
            return;
        }

        setBusy(true);
        showStatus("Đang ghép nối với Gỡ lỗi không dây…", false);

        executor.execute(() -> {
            String resultMessage;
            boolean error;
            try {
                LocalAdbConnectionManager manager = LocalAdbConnectionManager.getInstance(this);

                if (pairPort > 0) {
                    boolean paired = manager.pair("127.0.0.1", pairPort, code);
                    if (!paired) {
                        throw new IllegalStateException(
                                "Ghép nối không thành công. Hãy tạo MÃ GHÉP NỐI mới và giữ cửa sổ mã đang mở khi bấm cấp quyền."
                        );
                    }
                }

                boolean connected = manager.isConnected();
                if (!connected) {
                    if (adbPort > 0) {
                        connected = manager.connect("127.0.0.1", adbPort);
                    } else {
                        connected = manager.autoConnect(getApplicationContext(), 10_000L);
                    }
                }

                if (!connected && !manager.isConnected()) {
                    throw new IllegalStateException(
                            "Đã ghép nối nhưng chưa tìm thấy cổng kết nối ADB. "
                                    + "Hãy nhập Cổng kết nối ADB ở dòng “Địa chỉ IP & cổng” của màn hình Gỡ lỗi không dây rồi thử lại."
                    );
                }

                String command = "pm grant " + getPackageName()
                        + " android.permission.WRITE_SECURE_SETTINGS";
                String shellOutput = runOneShellCommand(manager, command);

                Thread.sleep(350L);
                if (!hasSecureSettingsPermission()) {
                    String detail = shellOutput.trim();
                    throw new IllegalStateException(
                            "ADB đã chạy nhưng quyền chưa được cấp."
                                    + (detail.isEmpty() ? "" : "\nPhản hồi: " + detail)
                    );
                }

                resultMessage = "THÀNH CÔNG!\nWRITE_SECURE_SETTINGS: ĐÃ CẤP\n\n"
                        + "Bây giờ bạn có thể TẮT Gỡ lỗi không dây. Không cần máy tính nữa.";
                error = false;
            } catch (Throwable throwable) {
                String detail = throwable.getMessage();
                if (detail == null || detail.trim().isEmpty()) {
                    detail = throwable.getClass().getSimpleName();
                }
                resultMessage = "CHƯA CẤP ĐƯỢC QUYỀN\n" + detail;
                error = true;
            }

            final String finalMessage = resultMessage;
            final boolean finalError = error;
            runOnUiThread(() -> {
                setBusy(false);
                showStatus(finalMessage, finalError);
                refreshStatus();
            });
        });
    }

    private String runOneShellCommand(LocalAdbConnectionManager manager, String command) throws Exception {
        AdbStream stream = manager.openStream("shell:" + command);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            try (InputStream input = stream.openInputStream()) {
                byte[] chunk = new byte[1024];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                    if (buffer.size() > 16 * 1024) {
                        break;
                    }
                }
            }
        } finally {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private void openWirelessDebuggingSettings() {
        try {
            Intent direct = new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS");
            if (direct.resolveActivity(getPackageManager()) != null) {
                startActivity(direct);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable throwable) {
            showStatus("Không mở được Tùy chọn nhà phát triển trên máy này.", true);
        }
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        if (hasSecureSettingsPermission()) {
            showStatus("WRITE_SECURE_SETTINGS: ĐÃ CẤP ✓\nKhông cần máy tính. Có thể tắt Gỡ lỗi không dây.", false);
        } else {
            showStatus("WRITE_SECURE_SETTINGS: CHƯA CẤP", true);
        }
    }

    private boolean hasSecureSettingsPermission() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void setBusy(boolean busy) {
        grantButton.setEnabled(!busy);
        pairingPortView.setEnabled(!busy);
        pairingCodeView.setEnabled(!busy);
        adbPortView.setEnabled(!busy);
    }

    private void showStatus(String message, boolean error) {
        statusView.setText(message);
        statusView.setTextColor(error
                ? Color.rgb(160, 55, 35)
                : Color.rgb(20, 110, 45));
    }

    private int parsePort(String value) {
        if (!isDigits(value)) {
            throw new IllegalArgumentException("Cổng phải là số.");
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Cổng không hợp lệ.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng phải nằm trong khoảng 1–65535.");
        }
        return port;
    }

    private boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private EditText numberField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextSize(16);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        return field;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        params.topMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private Button primaryButton(String label) {
        Button button = button(label);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(11, 87, 208));
        return button;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(Color.rgb(25, 25, 25));
        if (bold) {
            textView.setTypeface(textView.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return textView;
    }

    private LinearLayout.LayoutParams fullWidthWrap(int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(bottomMarginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
