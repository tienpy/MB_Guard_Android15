package com.mrtien.autoskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.TouchInteractionController;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
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

    private static final long DOUBLE_TAP_MIN_MS = 70L;
    private static final long DOUBLE_TAP_MAX_MS = 560L;
    private static final long PASTE_BUTTON_TIMEOUT_MS = 4500L;
    private static final long SCAN_INTERVAL_MS = 280L;
    private static final long AD_SCAN_BURST_MS = 22000L;

    private static final Set<String> EXACT_SKIP_LABELS = new HashSet<>(Arrays.asList(
            "skip", "skip ad", "skip ads", "skip video", "skip advertisement",
            "skip this ad", "close ad", "close ads", "close advertisement",
            "dismiss ad", "dismiss advertisement", "bo qua", "bo qua quang cao",
            "dong quang cao", "dong qc", "tat quang cao", "bo qua qc",
            "skip now", "close video", "continue to app", "back to app",
            "return to app", "continue", "tiep tuc vao ung dung",
            "quay lai ung dung", "tro lai ung dung"
    ));

    private static final String[] SKIP_PREFIXES = {
            "skip ad ", "skip in ", "skip video ", "skip advertisement ",
            "bo qua sau ", "bo qua quang cao ", "dong quang cao ", "dong qc ",
            "continue to app ", "back to app "
    };

    private static final String[] ID_HINTS = {
            "skip_ad", "skipad", "ad_skip", "adskip", "close_ad", "closead",
            "ad_close", "adclose", "dismiss_ad", "dismissad", "btn_skip_ad",
            "button_skip_ad", "reward_close", "interstitial_close", "ad_close_button",
            "skip_button", "skipbutton", "rewarded_close", "close_interstitial",
            "continue_to_app", "continue_to_content", "back_to_app"
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
            "com.android.inputmethod",
            "com.android.keyguard",
            "com.android.documentsui",
            "com.huawei.hidisk",
            "com.huawei.filemanager",
            "com.hihonor.filemanager",
            "com.huawei.android.launcher",
            "com.hihonor.android.launcher",
            "com.huawei.systemmanager",
            "com.hihonor.systemmanager"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;

    private String lastForegroundPackage = "";
    private long scanBurstUntil;
    private boolean scanScheduled;

    private AccessibilityNodeInfo focusedEditable;
    private String focusedEditableSignature = "";
    private final Rect focusedEditableBounds = new Rect();

    private TextView pasteOverlay;
    private AccessibilityNodeInfo pasteTarget;
    private String pasteTargetSignature = "";
    private final Runnable hidePasteRunnable = this::hidePasteOverlay;

    private long lastTapDownAt;
    private float lastTapDownX;
    private float lastTapDownY;
    private String lastTapDownSignature = "";

    private TouchInteractionController touchController;
    private TouchInteractionController.Callback touchCallback;
    private boolean touchCaptureRequested;

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scanScheduled = false;
            if (!isAutoSkipEnabled()) return;
            long now = SystemClock.elapsedRealtime();
            if (now > scanBurstUntil) return;

            boolean acted = scanAndSkipOnce();
            if (acted) {
                scanBurstUntil = Math.max(scanBurstUntil, now + AD_SCAN_BURST_MS);
            }
            scheduleNextScan();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        setupTouchController();
        startScanBurst(AD_SCAN_BURST_MS);
    }

    private void setupTouchController() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            touchController = getTouchInteractionController(Display.DEFAULT_DISPLAY);
            touchCallback = new TouchInteractionController.Callback() {
                @Override
                public void onMotionEvent(MotionEvent event) {
                    if (event == null || touchController == null) return;
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        handleRawTouchDown(event.getRawX(), event.getRawY());
                    }
                    try {
                        touchController.requestDelegating();
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onStateChanged(int state) {
                }
            };
            touchController.registerCallback(null, touchCallback);
        } catch (Throwable ignored) {
            touchController = null;
            touchCallback = null;
        }
    }

    private void setTouchCaptureRequested(boolean enabled) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (touchCaptureRequested == enabled) return;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            if (enabled) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            } else {
                info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            }
            setServiceInfo(info);
            touchCaptureRequested = enabled;
        } catch (Throwable ignored) {
        }
    }

    private void handleRawTouchDown(float x, float y) {
        if (!isDoubleTapPasteEnabled()) return;

        AccessibilityNodeInfo editable = findFocusedEditableInWindows();
        if (!isEditableNode(editable)) {
            resetTapSequence();
            return;
        }

        String pkg = editable.getPackageName() == null
                ? lastForegroundPackage
                : editable.getPackageName().toString();
        if (!isSafePackage(pkg)) {
            resetTapSequence();
            return;
        }

        rememberFocusedEditable(editable, pkg);
        Rect b = new Rect(focusedEditableBounds);
        if (!b.contains(Math.round(x), Math.round(y))) {
            resetTapSequence();
            return;
        }

        String signature = focusedEditableSignature;
        long now = SystemClock.elapsedRealtime();
        long delta = now - lastTapDownAt;
        float dx = x - lastTapDownX;
        float dy = y - lastTapDownY;
        float maxDistance = dp(55);

        if (signature.equals(lastTapDownSignature)
                && delta >= DOUBLE_TAP_MIN_MS
                && delta <= DOUBLE_TAP_MAX_MS
                && (dx * dx + dy * dy) <= maxDistance * maxDistance) {
            resetTapSequence();
            handler.postDelayed(this::showPasteOverlayFromFocusedInput, 90L);
        } else {
            lastTapDownAt = now;
            lastTapDownX = x;
            lastTapDownY = y;
            lastTapDownSignature = signature;
        }
    }

    private void resetTapSequence() {
        lastTapDownAt = 0L;
        lastTapDownX = 0f;
        lastTapDownY = 0f;
        lastTapDownSignature = "";
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() == null
                ? ""
                : event.getPackageName().toString();
        if (!packageName.isEmpty()) {
            lastForegroundPackage = packageName;
        }

        int type = event.getEventType();

        if (isAutoSkipEnabled() && isSafePackage(packageName)) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                startScanBurst(AD_SCAN_BURST_MS);
            } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                startScanBurst(9000L);
            }
        }

        if (!isDoubleTapPasteEnabled() || !isSafePackage(packageName)) {
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            AccessibilityNodeInfo editable = resolveEditableForEvent(event);
            if (editable != null) {
                rememberFocusedEditable(editable, packageName);
            }
        }

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            AccessibilityNodeInfo editable = resolveEditableForEvent(event);
            if (editable != null) {
                int start = editable.getTextSelectionStart();
                int end = editable.getTextSelectionEnd();
                // Fallback for OEMs that do not expose raw double taps: a real word selection
                // has a non-zero range, while normal typing keeps start == end.
                if (start >= 0 && end >= 0 && start != end) {
                    showPasteOverlay(editable, nodeSignature(editable, packageName));
                }
            }
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.postDelayed(() -> {
                AccessibilityNodeInfo editable = findFocusedEditableInWindows();
                if (editable == null) {
                    clearFocusedEditable();
                    setTouchCaptureRequested(false);
                }
            }, 350L);
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
        clearFocusedEditable();
        if (Build.VERSION.SDK_INT >= 33 && touchController != null) {
            try {
                if (touchCallback != null) touchController.unregisterCallback(touchCallback);
            } catch (Throwable ignored) {
            }
        }
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
        if (scanScheduled || SystemClock.elapsedRealtime() > scanBurstUntil) return;
        scanScheduled = true;
        handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS);
    }

    private boolean scanAndSkipOnce() {
        if (!isSafePackage(lastForegroundPackage)) return false;

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (int i = windows.size() - 1; i >= 0; i--) {
                    AccessibilityWindowInfo window = windows.get(i);
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && scanRootForAd(root)) return true;
                }
            }
        } catch (Throwable ignored) {
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && scanRootForAd(root);
    }

    private boolean scanRootForAd(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        AccessibilityNodeInfo xCandidate = null;

        while (!queue.isEmpty() && visited < 1500) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node == null) continue;

            if (node.isVisibleToUser() && node.isEnabled()) {
                String text = normalize(node.getText());
                String desc = normalize(node.getContentDescription());
                String hint = normalize(node.getHintText());
                String id = normalizeId(node.getViewIdResourceName());

                if (isStrongSkipLabel(text)
                        || isStrongSkipLabel(desc)
                        || isStrongSkipLabel(hint)
                        || hasStrongIdHint(id)) {
                    if (performClickOrTap(node)) {
                        incrementSkipCount();
                        return true;
                    }
                }

                if (xCandidate == null && isXLike(node, text, desc)) {
                    xCandidate = node;
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        // No blind screen-coordinate taps. They caused File Manager/Home to open by mistake.
        if (isCloseXEnabled() && xCandidate != null && performClickOrTap(xCandidate)) {
            incrementSkipCount();
            return true;
        }
        return false;
    }

    private boolean isStrongSkipLabel(String value) {
        if (value.isEmpty()) return false;
        if (EXACT_SKIP_LABELS.contains(value)) return true;
        for (String prefix : SKIP_PREFIXES) {
            if (value.startsWith(prefix)) return true;
        }
        if (value.matches("skip\\s*\\d+[a-z]*")) return true;
        if (value.matches("bo qua\\s*\\d+[a-z]*")) return true;
        return value.matches("close\\s*ad\\s*\\d*[a-z]*");
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
        boolean nearSide = r.centerX() > screenW * 0.56f || r.centerX() < screenW * 0.44f;
        boolean reasonablySmall = r.width() < screenW * 0.35f
                && r.height() < screenH * 0.22f;
        return nearTop && nearSide && reasonablySmall;
    }

    private boolean performClickOrTap(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 7; depth++) {
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

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return dispatchTap(r.centerX(), r.centerY());
    }

    private boolean dispatchTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 55L);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        try {
            return dispatchGesture(gesture, null, null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void incrementSkipCount() {
        SharedPreferences p = prefs();
        long value = p.getLong(MainActivity.KEY_SKIP_COUNT, 0L);
        p.edit().putLong(MainActivity.KEY_SKIP_COUNT, value + 1L).apply();
    }

    private AccessibilityNodeInfo resolveEditableForEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (isEditableNode(source)) return source;

        AccessibilityNodeInfo focused = findFocusedEditableInWindows();
        if (isEditableNode(focused)) return focused;
        return null;
    }

    private AccessibilityNodeInfo findFocusedEditableInWindows() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (int i = windows.size() - 1; i >= 0; i--) {
                    AccessibilityWindowInfo window = windows.get(i);
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (isEditableNode(focused)) return focused;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (isEditableNode(focused)) return focused;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void rememberFocusedEditable(AccessibilityNodeInfo node, String packageName) {
        if (!isEditableNode(node) || node.isPassword()) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return;

        focusedEditable = node;
        focusedEditableSignature = nodeSignature(node, packageName);
        focusedEditableBounds.set(bounds);
        setTouchCaptureRequested(true);
    }

    private boolean isEditableNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return false;
        if (node.isPassword()) return false;
        if (node.isEditable()) return true;

        CharSequence cls = node.getClassName();
        if (cls != null) {
            String c = cls.toString().toLowerCase(Locale.ROOT);
            if (c.contains("edittext") || c.contains("textfield") || c.contains("searchview")) {
                return true;
            }
        }

        try {
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                if (action.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT
                        || action.getId() == AccessibilityNodeInfo.ACTION_PASTE) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
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

    private void showPasteOverlayFromFocusedInput() {
        AccessibilityNodeInfo target = findFocusedEditableInWindows();
        if (target == null) target = focusedEditable;
        if (target == null) return;
        String pkg = target.getPackageName() == null
                ? lastForegroundPackage
                : target.getPackageName().toString();
        showPasteOverlay(target, nodeSignature(target, pkg));
    }

    private void showPasteOverlay(AccessibilityNodeInfo target, String signature) {
        if (windowManager == null || target == null) return;
        hidePasteOverlay();

        pasteTarget = target;
        pasteTargetSignature = signature;

        Rect bounds = new Rect();
        target.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return;

        TextView button = new TextView(this);
        button.setText("DÁN");
        button.setTextColor(Color.WHITE);
        button.setTextSize(16f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), dp(10), dp(18), dp(10));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(11, 143, 106));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), Color.argb(110, 255, 255, 255));
        button.setBackground(background);
        button.setElevation(dp(10));
        button.setOnClickListener(v -> pasteIntoTarget());
        pasteOverlay = button;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int w = dp(88);
        int h = dp(52);
        int x = Math.max(0, Math.min(bounds.right - w, screenW - w));
        int y = bounds.top > h + dp(8)
                ? bounds.top - h - dp(6)
                : Math.min(bounds.bottom + dp(6), screenH - h);

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
            handler.postDelayed(hidePasteRunnable, PASTE_BUTTON_TIMEOUT_MS);
        } catch (Throwable t) {
            pasteOverlay = null;
        }
    }

    private void pasteIntoTarget() {
        handler.removeCallbacks(hidePasteRunnable);
        AccessibilityNodeInfo target = findFocusedEditableInWindows();
        if (target == null) target = pasteTarget;
        boolean success = false;

        if (target != null) {
            try {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                success = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            } catch (Throwable ignored) {
            }
        }

        if (!success && target != null) {
            success = pasteWithSetTextFallback(target);
        }

        if (success) {
            SharedPreferences p = prefs();
            long count = p.getLong(MainActivity.KEY_PASTE_COUNT, 0L);
            p.edit().putLong(MainActivity.KEY_PASTE_COUNT, count + 1L).apply();
        } else {
            Toast.makeText(this, "Ô này không cho Android thực hiện Dán tự động.", Toast.LENGTH_SHORT).show();
        }
        hidePasteOverlay();
    }

    private boolean pasteWithSetTextFallback(AccessibilityNodeInfo target) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return false;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return false;

            CharSequence pasted = clip.getItemAt(0).coerceToText(this);
            if (pasted == null) return false;

            String oldText = target.getText() == null ? "" : target.getText().toString();
            int start = target.getTextSelectionStart();
            int end = target.getTextSelectionEnd();
            if (start < 0 || start > oldText.length()) start = oldText.length();
            if (end < 0 || end > oldText.length()) end = start;
            if (end < start) {
                int tmp = start;
                start = end;
                end = tmp;
            }

            String newText = oldText.substring(0, start)
                    + pasted
                    + oldText.substring(end);
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
            );
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Throwable ignored) {
            return false;
        }
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

    private void clearFocusedEditable() {
        focusedEditable = null;
        focusedEditableSignature = "";
        focusedEditableBounds.setEmpty();
        resetTapSequence();
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
        if (s.isEmpty()) return "";
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return s.replace('đ', 'd')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeId(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace(':', '_')
                .replace('/', '_')
                .replace('.', '_');
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
