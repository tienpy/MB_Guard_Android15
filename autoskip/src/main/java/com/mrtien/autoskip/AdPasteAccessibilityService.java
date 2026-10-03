package com.mrtien.autoskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityWindowInfo;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AdPasteAccessibilityService extends AccessibilityService {

    private static final long DOUBLE_TAP_MIN_MS = 80L;
    private static final long DOUBLE_TAP_MAX_MS = 650L;
    private static final long OVERLAY_TIMEOUT_MS = 3500L;
    private static final long SCAN_INTERVAL_MS = 220L;

    private static final Set<String> EXACT_SKIP_LABELS = new HashSet<>(Arrays.asList(
            "skip",
            "skip ad",
            "skip ads",
            "skip video",
            "skip advertisement",
            "skip this ad",
            "close ad",
            "close ads",
            "close advertisement",
            "dismiss ad",
            "dismiss advertisement",
            "bo qua",
            "bo qua quang cao",
            "dong quang cao",
            "dong qc",
            "tat quang cao"
    ));

    private static final String[] SKIP_PREFIXES = {
            "skip ad ",
            "skip in ",
            "skip video ",
            "bo qua sau ",
            "bo qua quang cao ",
            "dong quang cao "
    };

    private static final String[] ID_HINTS = {
            "skip_ad",
            "skipad",
            "ad_skip",
            "adskip",
            "close_ad",
            "closead",
            "ad_close",
            "adclose",
            "dismiss_ad",
            "dismissad",
            "btn_skip_ad",
            "button_skip_ad"
    };

    private static final String[] BLOCKED_PACKAGE_PREFIXES = {
            "com.mrtien.autoskip",
            "com.android.settings",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending",
            "com.google.android.inputmethod",
            "com.android.inputmethod"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long scanBurstUntil;
    private boolean scanScheduled;
    private String lastForegroundPackage = "";

    private long lastTapAt;
    private String lastTapSignature = "";

    private WindowManager windowManager;
    private TextView pasteOverlay;
    private AccessibilityNodeInfo pasteTarget;
    private String pasteTargetSignature = "";

    private final Runnable hidePasteRunnable = this::hidePasteOverlay;

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scanScheduled = false;
            if (!isAutoSkipEnabled()) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now > scanBurstUntil) {
                return;
            }

            boolean clicked = scanAndSkipOnce();
            if (clicked) {
                // Continue longer after every successful click so a second/third ad layer is handled.
                scanBurstUntil = Math.max(scanBurstUntil, now + 9000L);
            }
            scheduleNextScan();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence packageNameCs = event.getPackageName();
        String packageName = packageNameCs == null ? "" : packageNameCs.toString();
        if (!packageName.isEmpty()) {
            lastForegroundPackage = packageName;
        }

        int type = event.getEventType();

        if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                && isAutoSkipEnabled()
                && isSafePackage(packageName)) {
            startScanBurst(3200L);
        }

        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && isDoubleTapPasteEnabled()) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                try {
                    handleEditableTap(source, packageName);
                } finally {
                    // Do not recycle event source here; framework owns it.
                }
            }
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Do not leave a stale DÁN button over another screen.
            handler.removeCallbacks(hidePasteRunnable);
            hidePasteOverlay();
        }
    }

    @Override
    public void onInterrupt() {
        hidePasteOverlay();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hidePasteOverlay();
        super.onDestroy();
    }

    private boolean isAutoSkipEnabled() {
        return prefs().getBoolean(MainActivity.KEY_AUTO_SKIP, true);
    }

    private boolean isCloseXEnabled() {
        return prefs().getBoolean(MainActivity.KEY_CLOSE_X, true);
    }

    private boolean isDoubleTapPasteEnabled() {
        return prefs().getBoolean(MainActivity.KEY_DOUBLE_TAP_PASTE, true);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private void startScanBurst(long durationMs) {
        long now = SystemClock.elapsedRealtime();
        scanBurstUntil = Math.max(scanBurstUntil, now + durationMs);
        if (!scanScheduled) {
            scanScheduled = true;
            handler.post(scanRunnable);
        }
    }

    private void scheduleNextScan() {
        if (scanScheduled) return;
        if (SystemClock.elapsedRealtime() > scanBurstUntil) return;
        scanScheduled = true;
        handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS);
    }

    private boolean scanAndSkipOnce() {
        if (!isSafePackage(lastForegroundPackage)) {
            return false;
        }

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && clickBestAdCandidate(root)) {
                        incrementSkipCount();
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && clickBestAdCandidate(root)) {
            incrementSkipCount();
            return true;
        }
        return false;
    }

    private boolean clickBestAdCandidate(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        AccessibilityNodeInfo xCandidate = null;

        while (!queue.isEmpty() && visited < 900) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (node == null) continue;

            if (node.isVisibleToUser() && node.isEnabled()) {
                String text = normalize(node.getText());
                String desc = normalize(node.getContentDescription());
                String id = normalizeId(node.getViewIdResourceName());

                if (isStrongSkipLabel(text) || isStrongSkipLabel(desc) || hasStrongIdHint(id)) {
                    if (performClick(node)) {
                        return true;
                    }
                }

                if (isCloseXEnabled() && isXLike(node, text, desc)) {
                    xCandidate = node;
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }

        return xCandidate != null && performClick(xCandidate);
    }

    private boolean isStrongSkipLabel(String value) {
        if (value.isEmpty()) return false;
        if (EXACT_SKIP_LABELS.contains(value)) return true;
        for (String prefix : SKIP_PREFIXES) {
            if (value.startsWith(prefix)) return true;
        }

        // Common countdown formats: "Skip 5", "Skip 3s", "Bỏ qua 5".
        if (value.matches("skip\\s*\\d+[a-z]*")) return true;
        if (value.matches("bo qua\\s*\\d+[a-z]*")) return true;
        return false;
    }

    private boolean hasStrongIdHint(String id) {
        if (id.isEmpty()) return false;
        for (String hint : ID_HINTS) {
            if (id.contains(hint)) return true;
        }
        return false;
    }

    private boolean isXLike(AccessibilityNodeInfo node, String text, String desc) {
        String label = !text.isEmpty() ? text : desc;
        boolean xLabel = "x".equals(label)
                || "×".equals(label)
                || "✕".equals(label)
                || "✖".equals(label)
                || "close".equals(label)
                || "dismiss".equals(label)
                || "dong".equals(label);

        if (!xLabel) return false;

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (r.isEmpty() || screenW <= 0 || screenH <= 0) return false;

        boolean nearTop = r.centerY() < screenH * 0.38f;
        boolean nearSide = r.centerX() > screenW * 0.62f || r.centerX() < screenW * 0.38f;
        boolean reasonablySmall = r.width() < screenW * 0.28f && r.height() < screenH * 0.18f;
        return nearTop && nearSide && reasonablySmall;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current.isClickable() && current.isEnabled()) {
                try {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getParent();
        }
        return false;
    }

    private void incrementSkipCount() {
        SharedPreferences p = prefs();
        long value = p.getLong(MainActivity.KEY_SKIP_COUNT, 0L);
        p.edit().putLong(MainActivity.KEY_SKIP_COUNT, value + 1L).apply();
    }

    private void handleEditableTap(AccessibilityNodeInfo source, String packageName) {
        if (!isEditableNode(source)) {
            lastTapSignature = "";
            lastTapAt = 0L;
            return;
        }

        String signature = nodeSignature(source, packageName);
        long now = SystemClock.elapsedRealtime();
        long delta = now - lastTapAt;

        if (signature.equals(lastTapSignature)
                && delta >= DOUBLE_TAP_MIN_MS
                && delta <= DOUBLE_TAP_MAX_MS) {
            showPasteOverlay(source, signature);
            lastTapAt = 0L;
            lastTapSignature = "";
        } else {
            lastTapSignature = signature;
            lastTapAt = now;
        }
    }

    private boolean isEditableNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return false;
        if (node.isEditable()) return true;
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String c = cls.toString().toLowerCase(Locale.ROOT);
            if (c.contains("edittext") || c.contains("textfield")) return true;
        }
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT
                    || action.getId() == AccessibilityNodeInfo.ACTION_PASTE) {
                return true;
            }
        }
        return false;
    }

    private String nodeSignature(AccessibilityNodeInfo node, String packageName) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        String id = node.getViewIdResourceName();
        String cls = node.getClassName() == null ? "" : node.getClassName().toString();
        return packageName + "|" + (id == null ? "" : id) + "|" + cls + "|" + r.flattenToString();
    }

    private void showPasteOverlay(AccessibilityNodeInfo target, String signature) {
        if (windowManager == null) return;

        hidePasteOverlay();

        try {
            pasteTarget = AccessibilityNodeInfo.obtain(target);
            pasteTargetSignature = signature;
        } catch (Throwable t) {
            pasteTarget = target;
            pasteTargetSignature = signature;
        }

        Rect bounds = new Rect();
        target.getBoundsInScreen(bounds);

        TextView button = new TextView(this);
        button.setText("DÁN");
        button.setTextColor(Color.WHITE);
        button.setTextSize(15f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(9), dp(16), dp(9));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(11, 143, 106));
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        button.setElevation(dp(8));

        button.setOnClickListener(v -> pasteIntoTarget());
        pasteOverlay = button;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int overlayWidthGuess = dp(82);
        int overlayHeightGuess = dp(50);

        int x = Math.max(0, Math.min(bounds.right - overlayWidthGuess, screenW - overlayWidthGuess));
        int y;
        if (bounds.top > overlayHeightGuess + dp(8)) {
            y = bounds.top - overlayHeightGuess - dp(6);
        } else {
            y = Math.min(bounds.bottom + dp(6), screenH - overlayHeightGuess);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;

        try {
            windowManager.addView(button, lp);
            handler.removeCallbacks(hidePasteRunnable);
            handler.postDelayed(hidePasteRunnable, OVERLAY_TIMEOUT_MS);
        } catch (Throwable t) {
            pasteOverlay = null;
        }
    }

    private void pasteIntoTarget() {
        handler.removeCallbacks(hidePasteRunnable);

        AccessibilityNodeInfo target = pasteTarget;
        boolean success = false;

        if (target != null) {
            try {
                target.refresh();
            } catch (Throwable ignored) {
            }
            try {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                success = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            } catch (Throwable ignored) {
            }
        }

        if (!success) {
            AccessibilityNodeInfo reacquired = findEditableBySignature(pasteTargetSignature);
            if (reacquired != null) {
                try {
                    reacquired.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    success = reacquired.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                } catch (Throwable ignored) {
                }
            }
        }

        if (success) {
            SharedPreferences p = prefs();
            long count = p.getLong(MainActivity.KEY_PASTE_COUNT, 0L);
            p.edit().putLong(MainActivity.KEY_PASTE_COUNT, count + 1L).apply();
        } else {
            Toast.makeText(
                    this,
                    "Ô này không cho Accessibility thực hiện Dán. Hãy thử lại hoặc dùng menu Dán của Android.",
                    Toast.LENGTH_SHORT
            ).show();
        }

        hidePasteOverlay();
    }

    private AccessibilityNodeInfo findEditableBySignature(String signature) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || TextUtils.isEmpty(signature)) return null;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < 700) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node == null) continue;

            String pkg = node.getPackageName() == null ? "" : node.getPackageName().toString();
            if (isEditableNode(node) && signature.equals(nodeSignature(node, pkg))) {
                return node;
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private void hidePasteOverlay() {
        if (pasteOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(pasteOverlay);
            } catch (Throwable ignored) {
            }
        }
        pasteOverlay = null;
        pasteTarget = null;
        pasteTargetSignature = "";
    }

    private boolean isSafePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        String lower = packageName.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_PACKAGE_PREFIXES) {
            if (lower.startsWith(blocked)) return false;
        }
        return true;
    }

    private String normalize(CharSequence value) {
        if (value == null) return "";
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        s = s.replace('đ', 'd');
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    private String normalizeId(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
