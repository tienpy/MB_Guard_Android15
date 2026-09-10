package com.mrtien.tiencallrecorder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HONOR Magic V3 helper.
 *
 * The actual call audio is recorded by HONOR's built-in call recorder. This
 * accessibility service only presses the same Record/Ghi am control that the
 * user can press manually on the in-call screen.
 */
public class RecorderAccessibilityService extends AccessibilityService {
    private static final long RETRY_MS = 350L;
    private static final long MAX_SEARCH_MS = 25000L;
    private static final int MIN_CANDIDATE_SCORE = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TelephonyManager telephonyManager;
    private PhoneCallback phoneCallback;

    private boolean callActive;
    private boolean recordPressedThisCall;
    private boolean pressAttemptInFlight;
    private long callConnectedAt;

    private final Runnable retryRecord = new Runnable() {
        @Override
        public void run() {
            if (!callActive || recordPressedThisCall || !autoHonorEnabled()) {
                return;
            }
            if (System.currentTimeMillis() - callConnectedAt > MAX_SEARCH_MS) {
                setHonorStatus("Không tìm thấy nút Ghi âm HONOR trong 25 giây đầu cuộc gọi");
                return;
            }
            tryPressHonorRecord();
            if (!recordPressedThisCall && !pressAttemptInFlight) {
                handler.postDelayed(this, RETRY_MS);
            }
        }
    };

    private final class PhoneCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            handleCallState(state);
        }
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        int score;
        String label;
        String packageName;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        registerPhoneState();
        setHonorStatus("Trợ năng HONOR đã sẵn sàng chờ cuộc gọi");
    }

    private void registerPhoneState() {
        if (telephonyManager == null) {
            setHonorStatus("Không lấy được TelephonyManager");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                phoneCallback = new PhoneCallback();
                telephonyManager.registerTelephonyCallback(getMainExecutor(), phoneCallback);
            } else {
                telephonyManager.listen(new android.telephony.PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        handleCallState(state);
                    }
                }, android.telephony.PhoneStateListener.LISTEN_CALL_STATE);
            }
        } catch (Throwable t) {
            setHonorStatus("Không đăng ký được trạng thái cuộc gọi: " + t.getClass().getSimpleName());
        }
    }

    private void handleCallState(int state) {
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            callActive = true;
            recordPressedThisCall = false;
            pressAttemptInFlight = false;
            callConnectedAt = System.currentTimeMillis();
            setHonorStatus("Đã kết nối cuộc gọi - đang tìm nút Ghi âm HONOR");
            handler.removeCallbacks(retryRecord);
            handler.postDelayed(retryRecord, 180L);
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            callActive = false;
            recordPressedThisCall = false;
            pressAttemptInFlight = false;
            handler.removeCallbacks(retryRecord);
            setHonorStatus("Cuộc gọi đã kết thúc");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!callActive || recordPressedThisCall || !autoHonorEnabled()) {
            return;
        }
        tryPressHonorRecord();
    }

    private void tryPressHonorRecord() {
        if (pressAttemptInFlight || recordPressedThisCall) {
            return;
        }

        List<AccessibilityNodeInfo> roots = collectRoots();
        if (roots.isEmpty()) {
            setScanStatus("Không lấy được cây giao diện cuộc gọi");
            return;
        }

        Candidate best = null;
        ArrayList<String> debugHits = new ArrayList<>();

        for (AccessibilityNodeInfo root : roots) {
            if (root == null) continue;

            String packageName = root.getPackageName() == null
                    ? ""
                    : root.getPackageName().toString();

            // Never click our own settings screen.
            if (getPackageName().equals(packageName)) {
                continue;
            }

            if (findActiveRecordingNode(root) != null) {
                recordPressedThisCall = true;
                setHonorStatus("HONOR đang ghi âm cuộc gọi");
                setScanStatus("Đã thấy trạng thái đang ghi trên giao diện " + packageName);
                return;
            }

            Candidate candidate = findBestRecordCandidate(root, packageName, debugHits);
            if (candidate != null && (best == null || candidate.score > best.score)) {
                best = candidate;
            }
        }

        setScanStatus(buildScanSummary(best, debugHits));

        if (best == null || best.score < MIN_CANDIDATE_SCORE || best.node == null) {
            return;
        }

        pressAttemptInFlight = true;
        setHonorStatus(
                "Đã tìm thấy nút Ghi âm (điểm " + best.score + ") - đang bấm"
        );

        if (performNodeClick(best.node)) {
            onPressSent("ACTION_CLICK", best);
            return;
        }

        if (tapNodeCenter(best.node, best)) {
            return;
        }

        pressAttemptInFlight = false;
        setHonorStatus("Tìm thấy nút Ghi âm nhưng Android không cho bấm");
    }

    private List<AccessibilityNodeInfo> collectRoots() {
        ArrayList<AccessibilityNodeInfo> roots = new ArrayList<>();

        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) {
                roots.add(active);
            }
        } catch (Throwable ignored) {
        }

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && !roots.contains(root)) {
                        roots.add(root);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return roots;
    }

    private AccessibilityNodeInfo findActiveRecordingNode(AccessibilityNodeInfo node) {
        if (node == null) return null;

        String label = normalize(labelOf(node));
        if (!label.isEmpty()) {
            if (label.contains("dang ghi")
                    || label.contains("recording")
                    || label.contains("dung ghi am")
                    || label.contains("stop recording")
                    || label.contains("ket thuc ghi am")
                    || label.contains("stop record")) {
                return node;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findActiveRecordingNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private Candidate findBestRecordCandidate(
            AccessibilityNodeInfo root,
            String packageName,
            ArrayList<String> debugHits
    ) {
        Candidate best = new Candidate();
        best.score = Integer.MIN_VALUE;
        scanCandidate(root, packageName, best, debugHits, 0);
        return best.node == null ? null : best;
    }

    private void scanCandidate(
            AccessibilityNodeInfo node,
            String packageName,
            Candidate best,
            ArrayList<String> debugHits,
            int depth
    ) {
        if (node == null || depth > 40) return;

        String label = normalize(labelOf(node));
        String id = normalize(node.getViewIdResourceName());
        String clazz = normalize(node.getClassName() == null ? "" : node.getClassName().toString());

        if (looksInteresting(label, id) && debugHits.size() < 12) {
            debugHits.add(shorten(packageName + " | " + label + " | " + id, 110));
        }

        int score = scoreRecordCandidate(label, id, clazz, packageName, node);
        if (score > best.score) {
            best.score = score;
            best.node = node;
            best.label = label.isEmpty() ? id : label;
            best.packageName = packageName;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            scanCandidate(node.getChild(i), packageName, best, debugHits, depth + 1);
        }
    }

    private int scoreRecordCandidate(
            String label,
            String id,
            String clazz,
            String packageName,
            AccessibilityNodeInfo node
    ) {
        String all = label + " " + id;

        if (all.contains("dung ghi")
                || all.contains("stop record")
                || all.contains("recording")
                || all.contains("dang ghi")
                || all.contains("end record")) {
            return Integer.MIN_VALUE;
        }

        int score = 0;

        if (label.equals("ghi am") || label.equals("record")) score += 100;
        if (label.contains("ghi am cuoc goi")) score += 95;
        if (label.contains("ghi am")) score += 82;
        if (label.contains("record call") || label.contains("call record")) score += 85;
        if (label.contains("record")) score += 65;

        if (id.contains("call_record") || id.contains("call record")) score += 100;
        if (id.contains("record_button") || id.contains("record button")) score += 95;
        if (id.contains("record")) score += 70;
        if (id.contains("recorder")) score += 60;

        String pkg = normalize(packageName);
        if (pkg.contains("incall")
                || pkg.contains("dialer")
                || pkg.contains("phone")
                || pkg.contains("telecom")
                || pkg.contains("contacts")
                || pkg.contains("honor")
                || pkg.contains("huawei")) {
            score += 20;
        }

        if (node.isClickable()) score += 12;
        if (node.isEnabled()) score += 8;
        if (node.isVisibleToUser()) score += 8;
        if (clazz.contains("button") || clazz.contains("imagebutton")) score += 8;

        return score;
    }

    private boolean looksInteresting(String label, String id) {
        String all = label + " " + id;
        return all.contains("ghi")
                || all.contains("record")
                || all.contains("call")
                || all.contains("cuoc goi");
    }

    private boolean performNodeClick(AccessibilityNodeInfo node) {
        try {
            AccessibilityNodeInfo current = node;
            int hops = 0;
            while (current != null && hops < 6) {
                if (current.isClickable() && current.isEnabled()) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
                current = current.getParent();
                hops++;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean tapNodeCenter(AccessibilityNodeInfo node, Candidate candidate) {
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) {
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null) parent.getBoundsInScreen(bounds);
            }
            if (bounds.isEmpty()) return false;

            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            Path path = new Path();
            path.moveTo(x, y);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 70);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();

            boolean accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            onPressSent("TOUCH", candidate);
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            pressAttemptInFlight = false;
                            setHonorStatus("Android đã hủy cú chạm vào nút Ghi âm");
                            handler.postDelayed(retryRecord, 500L);
                        }
                    },
                    null
            );

            if (!accepted) {
                pressAttemptInFlight = false;
            }
            return accepted;
        } catch (Throwable t) {
            pressAttemptInFlight = false;
            return false;
        }
    }

    private void onPressSent(String method, Candidate candidate) {
        recordPressedThisCall = true;
        pressAttemptInFlight = false;

        int count = getSharedPreferences("prefs", MODE_PRIVATE)
                .getInt("honor_auto_click_count", 0) + 1;

        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putInt("honor_auto_click_count", count)
                .putString("last_honor_button", candidate == null ? "" : candidate.label)
                .putString("last_honor_package", candidate == null ? "" : candidate.packageName)
                .apply();

        setHonorStatus("ĐÃ GỬI LỆNH BẤM Ghi âm HONOR bằng " + method);
        Toast.makeText(
                this,
                "Đã bấm Ghi âm cuộc gọi HONOR",
                Toast.LENGTH_SHORT
        ).show();
    }

    private String buildScanSummary(Candidate best, ArrayList<String> hits) {
        StringBuilder out = new StringBuilder();
        if (best != null) {
            out.append("Ứng viên tốt nhất: ")
                    .append(best.label)
                    .append(" | điểm=")
                    .append(best.score)
                    .append(" | pkg=")
                    .append(best.packageName == null ? "" : best.packageName);
        } else {
            out.append("Không thấy node có chữ/id liên quan Ghi âm");
        }

        if (!hits.isEmpty()) {
            out.append("\nUI thấy: ");
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) out.append(" ; ");
                out.append(hits.get(i));
            }
        }
        return shorten(out.toString(), 900);
    }

    private String labelOf(AccessibilityNodeInfo node) {
        StringBuilder value = new StringBuilder();
        if (node.getText() != null) {
            value.append(node.getText()).append(' ');
        }
        if (node.getContentDescription() != null) {
            value.append(node.getContentDescription()).append(' ');
        }
        if (node.getHintText() != null) {
            value.append(node.getHintText()).append(' ');
        }
        if (node.getViewIdResourceName() != null) {
            value.append(node.getViewIdResourceName());
        }
        return value.toString();
    }

    private String normalize(String source) {
        if (source == null) return "";
        return Normalizer.normalize(source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .trim();
    }

    private String shorten(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max) + "...";
    }

    private boolean autoHonorEnabled() {
        return getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("auto_honor_system", true);
    }

    private void setHonorStatus(String text) {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("last_honor_event", text)
                .putLong("last_honor_event_time", System.currentTimeMillis())
                .apply();
    }

    private void setScanStatus(String text) {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("last_honor_scan", text)
                .apply();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 31
                && telephonyManager != null
                && phoneCallback != null) {
            try {
                telephonyManager.unregisterTelephonyCallback(phoneCallback);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }
}
