package com.mrtien.mbbankguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private TextView permissionStatus;
    private TextView quickAppStatus;
    private TextView serviceStatus;
    private TextView runtimeStatus;
    private TextView messageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AccessibilityController.removeLegacyGuardMonitor(this);
        setContentView(createContentView());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("MB Guard – Android 15", 27, true);
        title.setTextColor(Color.rgb(7, 59, 140));
        root.addView(title);

        TextView description = text(
                "Ngoài màn hình chính chỉ còn DUY NHẤT một biểu tượng “BẬT TẮT TRỢ NĂNG”. "
                        + "Bấm một lần để OFF, bấm lần nữa để ON lại các dịch vụ đã chọn. "
                        + "Muốn mở phần cài đặt MB Guard, hãy NHẤN GIỮ biểu tượng đó rồi chọn “Cài đặt MB Guard”.",
                16,
                false
        );
        description.setPadding(0, dp(8), 0, dp(14));
        root.addView(description);

        permissionStatus = statusCard(root, "1. Quyền cần thiết");

        Button adbButton = primaryButton("CẤP QUYỀN TRÊN ĐIỆN THOẠI");
        adbButton.setOnClickListener(v -> startActivity(
                new Intent(this, LocalAdbPermissionActivity.class)
        ));
        root.addView(adbButton);

        Button usageButton = button("Mở quyền Usage Access");
        usageButton.setOnClickListener(v -> openUsageAccessSettings());
        root.addView(usageButton);

        if (isOldGuardInstalled()) {
            Button removeOldButton = button("GỠ MB GUARD CŨ / XÓA CÁC ICON CŨ");
            removeOldButton.setOnClickListener(v -> uninstallOldGuard());
            root.addView(removeOldButton);
        }

        quickAppStatus = statusCard(root, "2. Ứng dụng cần bảo vệ");

        Button selectAppButton = button("Chọn nhiều ứng dụng cần bảo vệ");
        selectAppButton.setOnClickListener(v -> showQuickAppSelectionDialog());
        root.addView(selectAppButton);

        serviceStatus = statusCard(root, "3. Accessibility cần tắt tạm thời");

        Button selectServicesButton = button("Chọn dịch vụ Accessibility");
        selectServicesButton.setOnClickListener(v -> showServiceSelectionDialog());
        root.addView(selectServicesButton);

        Button toggleNowButton = primaryButton("BẬT / TẮT TRỢ NĂNG ĐÃ CHỌN NGAY");
        toggleNowButton.setOnClickListener(v -> toggleAccessibilityNow());
        root.addView(toggleNowButton);

        runtimeStatus = statusCard(root, "4. Trạng thái hoạt động");

        Button testButton = primaryButton("THỬ TẮT ACCESSIBILITY MỘT CHẠM");
        testButton.setOnClickListener(v -> startActivity(
                new Intent(this, SafeLaunchActivity.class)
        ));
        root.addView(testButton);

        Button restoreButton = button("Bật lại Accessibility ngay");
        restoreButton.setOnClickListener(v -> restoreNow());
        root.addView(restoreButton);

        Button appSettingsButton = button("Mở cài đặt ứng dụng / pin");
        appSettingsButton.setOnClickListener(v -> openAppSettings());
        root.addView(appSettingsButton);

        messageView = text("", 15, false);
        messageView.setPadding(0, dp(14), 0, 0);
        root.addView(messageView);

        TextView note = text(
                "Bản này không còn tạo icon “MB Guard Android 15” hay “MỞ APP AN TOÀN” ngoài màn hình. "
                        + "Chỉ icon công tắc “BẬT TẮT TRỢ NĂNG” được giữ lại. "
                        + "Nếu máy vẫn còn icon xanh dương của bản cũ, hãy dùng nút “GỠ MB GUARD CŨ” ở phía trên.",
                14,
                false
        );
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        return scrollView;
    }

    private TextView statusCard(LinearLayout parent, String heading) {
        TextView headingView = text(heading, 18, true);
        headingView.setPadding(0, dp(14), 0, dp(5));
        parent.addView(headingView);

        TextView status = text("Đang kiểm tra…", 15, false);
        status.setBackgroundColor(Color.WHITE);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        parent.addView(status, params);
        return status;
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

    private void refreshStatus() {
        boolean secure = AccessibilityController.hasWriteSecureSettings(this);
        boolean usage = ForegroundAppDetector.hasUsageAccess(this);
        permissionStatus.setText(
                "WRITE_SECURE_SETTINGS: " + (secure ? "ĐÃ CẤP" : "CHƯA CẤP")
                        + "\nUsage Access: " + (usage ? "ĐÃ CẤP" : "CHƯA CẤP")
                        + "\nAccessibility Monitor của Guard: ĐÃ LOẠI BỎ"
        );
        permissionStatus.setTextColor(secure && usage
                ? Color.rgb(20, 110, 45)
                : Color.rgb(160, 55, 35));

        List<MonitoredAppsStore.InstalledApp> selectedApps =
                MonitoredAppsStore.getSelectedInstalledApps(this);
        StringBuilder appsText = new StringBuilder();
        boolean allReady = !selectedApps.isEmpty();
        for (MonitoredAppsStore.InstalledApp app : selectedApps) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(app.packageName);
            if (appsText.length() > 0) {
                appsText.append('\n');
            }
            appsText.append(launchIntent == null ? "MẤT • " : "OK   • ")
                    .append(app.label)
                    .append("\n     ")
                    .append(app.packageName);
            if (launchIntent == null) {
                allReady = false;
            }
        }
        if (appsText.length() == 0) {
            appsText.append("Chưa chọn ứng dụng nào.");
        }
        quickAppStatus.setText(
                "Đã chọn " + selectedApps.size() + " ứng dụng\n" + appsText
        );
        quickAppStatus.setTextColor(allReady
                ? Color.rgb(20, 110, 45)
                : Color.rgb(160, 55, 35));

        List<AccessibilityController.InstalledService> installed =
                AccessibilityController.getInstalledServices(this);
        Set<ComponentName> selected = AccessibilityController.getSelectedComponents(this);
        StringBuilder selectedText = new StringBuilder();
        int selectedOnCount = 0;
        for (AccessibilityController.InstalledService service : installed) {
            if (selected.contains(service.componentName)) {
                if (selectedText.length() > 0) {
                    selectedText.append('\n');
                }
                if (service.enabled) {
                    selectedOnCount++;
                }
                selectedText.append(service.enabled ? "ON  • " : "OFF • ")
                        .append(service.label);
            }
        }
        if (selectedText.length() == 0) {
            selectedText.append("Chưa chọn dịch vụ nào.");
        }
        serviceStatus.setText("Đã chọn " + selected.size() + " dịch vụ"
                + "\nĐang ON: " + selectedOnCount + "/" + selected.size()
                + "\n" + selectedText
                + (selected.isEmpty() ? ""
                : selectedOnCount == 0
                ? "\n→ Hiện tất cả đang OFF. Bấm nút BẬT / TẮT bên dưới để bật."
                : ""));
        serviceStatus.setTextColor(selected.isEmpty() || selectedOnCount == 0
                ? Color.rgb(160, 55, 35)
                : Color.rgb(20, 110, 45));

        boolean armed = RecoveryWatchdogService.isArmed(this);
        boolean active = AccessibilityController.isGuardActive(this);
        String lastRestore = getSharedPreferences("guard_prefs", MODE_PRIVATE)
                .getString("last_restore_message", "Chưa có phiên bảo vệ gần đây.");
        runtimeStatus.setText(
                "Khi chờ: KHÔNG CHẠY NỀN"
                        + "\nWatchdog tạm thời: " + (armed ? "ĐANG CHẠY" : "ĐÃ DỪNG")
                        + "\nAccessibility đang tắt tạm: " + (active ? "CÓ" : "KHÔNG")
                        + "\nLần gần nhất: " + lastRestore
        );
        runtimeStatus.setTextColor(armed || active
                ? Color.rgb(180, 105, 10)
                : Color.rgb(20, 110, 45));
    }

    private void showQuickAppSelectionDialog() {
        List<MonitoredAppsStore.InstalledApp> installed =
                MonitoredAppsStore.getLaunchableApps(this);
        if (installed.isEmpty()) {
            showMessage("Không tìm thấy ứng dụng có thể mở trên máy.", true);
            return;
        }

        LinkedHashSet<String> chosen = new LinkedHashSet<>(
                MonitoredAppsStore.getSelectedPackages(this)
        );
        ArrayList<MonitoredAppsStore.InstalledApp> filtered = new ArrayList<>();
        EditText searchView = createSearchView("Tìm tên ứng dụng hoặc package…");
        TextView summaryView = createPickerSummary();
        ListView listView = createPickerList(ListView.CHOICE_MODE_MULTIPLE);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_multiple_choice,
                new ArrayList<>()
        );
        listView.setAdapter(adapter);

        LinearLayout content = createPickerContent(searchView, summaryView, listView);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Chọn nhiều ứng dụng cần bảo vệ")
                .setView(content)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", null)
                .create();

        Runnable refresh = () -> updateFilteredApps(
                searchView.getText().toString(),
                installed,
                filtered,
                chosen,
                adapter,
                listView,
                summaryView
        );
        searchView.addTextChangedListener(new SimpleTextWatcher(refresh));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filtered.size()) {
                return;
            }
            String packageName = filtered.get(position).packageName;
            if (listView.isItemChecked(position)) {
                chosen.add(packageName);
            } else {
                chosen.remove(packageName);
            }
            updatePickerSummary(summaryView, filtered.size(), installed.size(), chosen.size());
        });

        dialog.setOnShowListener(ignored -> {
            refresh.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                MonitoredAppsStore.saveSelectedPackages(this, chosen);
                showMessage(chosen.isEmpty()
                                ? "Bạn chưa chọn ứng dụng nào cần bảo vệ."
                                : "Đã lưu " + chosen.size() + " ứng dụng cần bảo vệ.",
                        chosen.isEmpty());
                refreshStatus();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showCreateShortcutDialog() {
        List<MonitoredAppsStore.InstalledApp> selectedApps =
                MonitoredAppsStore.getSelectedInstalledApps(this);
        if (selectedApps.isEmpty()) {
            showMessage("Hãy chọn ít nhất một ứng dụng cần bảo vệ trước.", true);
            return;
        }
        if (!HomeShortcutHelper.isSupported(this)) {
            showMessage("Màn hình chính của điện thoại không hỗ trợ tạo phím tắt tự động.", true);
            return;
        }

        CharSequence[] labels = new CharSequence[selectedApps.size()];
        for (int index = 0; index < selectedApps.size(); index++) {
            MonitoredAppsStore.InstalledApp app = selectedApps.get(index);
            labels[index] = app.label + "\n" + app.packageName;
        }

        new AlertDialog.Builder(this)
                .setTitle("Tạo phím tắt mở thẳng ứng dụng")
                .setItems(labels, (dialog, which) -> {
                    MonitoredAppsStore.InstalledApp app = selectedApps.get(which);
                    boolean requested = HomeShortcutHelper.requestPinnedShortcut(this, app);
                    showMessage(requested
                                    ? "Hãy xác nhận thêm phím tắt “" + app.label + "” trên màn hình chính."
                                    : "Không gửi được yêu cầu tạo phím tắt.",
                            !requested);
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void showServiceSelectionDialog() {
        List<AccessibilityController.InstalledService> installed =
                AccessibilityController.getInstalledServices(this);
        if (installed.isEmpty()) {
            showMessage("Không tìm thấy dịch vụ Accessibility nào trên máy.", true);
            return;
        }

        LinkedHashSet<ComponentName> chosen = new LinkedHashSet<>(
                AccessibilityController.getSelectedComponents(this)
        );
        ArrayList<AccessibilityController.InstalledService> filtered = new ArrayList<>();
        EditText searchView = createSearchView("Tìm tên dịch vụ hoặc package…");
        TextView summaryView = createPickerSummary();
        ListView listView = createPickerList(ListView.CHOICE_MODE_MULTIPLE);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_multiple_choice,
                new ArrayList<>()
        );
        listView.setAdapter(adapter);

        LinearLayout content = createPickerContent(searchView, summaryView, listView);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Chọn Accessibility cần tắt")
                .setView(content)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", null)
                .create();

        Runnable refresh = () -> updateFilteredServices(
                searchView.getText().toString(),
                installed,
                filtered,
                chosen,
                adapter,
                listView,
                summaryView
        );
        searchView.addTextChangedListener(new SimpleTextWatcher(refresh));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filtered.size()) {
                return;
            }
            ComponentName component = filtered.get(position).componentName;
            if (listView.isItemChecked(position)) {
                chosen.add(component);
            } else {
                chosen.remove(component);
            }
            updatePickerSummary(summaryView, filtered.size(), installed.size(), chosen.size());
        });

        dialog.setOnShowListener(ignored -> {
            refresh.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                AccessibilityController.saveSelectedComponents(this, chosen);
                showMessage(chosen.isEmpty()
                        ? "Bạn chưa chọn dịch vụ Accessibility nào."
                        : "Đã lưu " + chosen.size() + " dịch vụ Accessibility.",
                        chosen.isEmpty());
                refreshStatus();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private EditText createSearchView(String hint) {
        EditText searchView = new EditText(this);
        searchView.setHint(hint);
        searchView.setSingleLine(true);
        searchView.setTextSize(16);
        searchView.setPadding(dp(12), dp(8), dp(12), dp(8));
        return searchView;
    }

    private TextView createPickerSummary() {
        TextView summary = text("", 14, false);
        summary.setTextColor(Color.DKGRAY);
        summary.setPadding(dp(4), dp(8), dp(4), dp(8));
        return summary;
    }

    private ListView createPickerList(int choiceMode) {
        ListView listView = new ListView(this);
        listView.setChoiceMode(choiceMode);
        listView.setFastScrollEnabled(true);
        listView.setDividerHeight(1);
        return listView;
    }

    private LinearLayout createPickerContent(
            EditText searchView,
            TextView summaryView,
            ListView listView
    ) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), 0);
        content.addView(searchView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        content.addView(summaryView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        int listHeight = Math.min(
                dp(480),
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.58f)
        );
        content.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                listHeight
        ));
        return content;
    }

    private void updateFilteredApps(
            String query,
            List<MonitoredAppsStore.InstalledApp> installed,
            List<MonitoredAppsStore.InstalledApp> filtered,
            Set<String> chosen,
            ArrayAdapter<String> adapter,
            ListView listView,
            TextView summaryView
    ) {
        String normalizedQuery = normalizeSearch(query);
        filtered.clear();
        adapter.clear();
        for (MonitoredAppsStore.InstalledApp app : installed) {
            String searchable = normalizeSearch(app.label + " " + app.packageName);
            if (normalizedQuery.isEmpty() || searchable.contains(normalizedQuery)) {
                filtered.add(app);
                adapter.add(app.label + "\n" + app.packageName);
            }
        }
        adapter.notifyDataSetChanged();
        listView.clearChoices();
        for (int index = 0; index < filtered.size(); index++) {
            listView.setItemChecked(index, chosen.contains(filtered.get(index).packageName));
        }
        updatePickerSummary(summaryView, filtered.size(), installed.size(), chosen.size());
    }

    private void updateFilteredServices(
            String query,
            List<AccessibilityController.InstalledService> installed,
            List<AccessibilityController.InstalledService> filtered,
            Set<ComponentName> chosen,
            ArrayAdapter<String> adapter,
            ListView listView,
            TextView summaryView
    ) {
        String normalizedQuery = normalizeSearch(query);
        filtered.clear();
        adapter.clear();
        for (AccessibilityController.InstalledService service : installed) {
            String searchable = normalizeSearch(
                    service.label + " " + service.flattened()
            );
            if (normalizedQuery.isEmpty() || searchable.contains(normalizedQuery)) {
                filtered.add(service);
                adapter.add((service.enabled ? "ON  • " : "OFF • ")
                        + service.label + "\n" + service.flattened());
            }
        }
        adapter.notifyDataSetChanged();
        listView.clearChoices();
        for (int index = 0; index < filtered.size(); index++) {
            listView.setItemChecked(index, chosen.contains(filtered.get(index).componentName));
        }
        updatePickerSummary(summaryView, filtered.size(), installed.size(), chosen.size());
    }

    private void updatePickerSummary(
            TextView summary,
            int visibleCount,
            int totalCount,
            int selectedCount
    ) {
        summary.setText("Đang hiện " + visibleCount + "/" + totalCount
                + " mục  •  Đã chọn " + selectedCount);
    }

    private String normalizeSearch(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String vietnameseSafe = value.replace('Đ', 'D').replace('đ', 'd');
        String decomposed = Normalizer.normalize(vietnameseSafe, Normalizer.Form.NFD);
        return decomposed
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }

    private void toggleAccessibilityNow() {
        RecoveryWatchdogService.cancelWithoutRestore(this);
        AccessibilityController.Result result =
                AccessibilityController.toggleSelectedServicesManually(this);
        showMessage(result.message, !result.success);
        refreshStatus();
    }

    private void restoreNow() {
        if (RecoveryWatchdogService.isArmed(this)) {
            RecoveryWatchdogService.stopAndRestore(this);
            showMessage("Đang dừng watchdog và bật lại Accessibility…", false);
        } else {
            AccessibilityController.Result result =
                    AccessibilityController.restoreSelectedServices(this);
            showMessage(result.message, !result.success);
        }
        refreshStatus();
    }

    private void openUsageAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception exception) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private static final String OLD_GUARD_PACKAGE = "com.mrtien.mbbankguard";

    private boolean isOldGuardInstalled() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                getPackageManager().getPackageInfo(
                        OLD_GUARD_PACKAGE,
                        PackageManager.PackageInfoFlags.of(0)
                );
            } else {
                getPackageManager().getPackageInfo(OLD_GUARD_PACKAGE, 0);
            }
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    private void uninstallOldGuard() {
        if (!isOldGuardInstalled()) {
            showMessage("Bản MB Guard cũ đã được gỡ.", false);
            return;
        }
        Intent uninstall = new Intent(
                Intent.ACTION_DELETE,
                Uri.parse("package:" + OLD_GUARD_PACKAGE)
        );
        startActivity(uninstall);
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void showMessage(String message, boolean error) {
        messageView.setText(message);
        messageView.setTextColor(error
                ? Color.rgb(160, 35, 35)
                : Color.rgb(20, 110, 45));
        Toast.makeText(this, firstLine(message), Toast.LENGTH_SHORT).show();
    }

    private String firstLine(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SimpleTextWatcher implements TextWatcher {
        private final Runnable onChanged;

        SimpleTextWatcher(Runnable onChanged) {
            this.onChanged = onChanged;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            onChanged.run();
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }
}
