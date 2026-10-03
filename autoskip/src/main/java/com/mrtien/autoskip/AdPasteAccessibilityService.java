package com.mrtien.autoskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
    private static final long DOUBLE_TAP_MAX_MS = 520L;
    private static final long OVERLAY_TIMEOUT_MS = 4200L;
    private static final long CAPTURE_TIMEOUT_MS = 8000L;
    private static final long SCAN_INTERVAL_MS = 260L;
    private static final long AD_SCAN_BURST_MS = 25000L;
    private static final long BLIND_AD_TAP_GAP_MS = 850L;

    private static final Set<String> EXACT_SKIP_LABELS = new HashSet<>(Arrays.asList(
            "skip", "skip ad", "skip ads", "skip video", "skip advertisement",
            "skip this ad", "close ad", "close ads", "close advertisement",
            "dismiss ad", "dismiss advertisement", "bo qua", "bo qua quang cao",
            "dong quang cao", "dong qc", "tat quang cao", "bo qua qc",
            "close video", "skip now", "dismiss"
    ));

    private static final String[] SKIP_PREFIXES = {
            "skip ad ", "skip in ", "skip video ", "skip advertisement ",
            "bo qua sau ", "bo qua quang cao ", "dong quang cao ", "dong qc "
    };

    private static final String[] ID_HINTS = {
            "skip_ad", "skipad", "ad_skip", "adskip", "close_ad", "closead",
            "ad_close", "adclose", "dismiss_ad", "dismissad", "btn_skip_ad",
            "button_skip_ad", "reward_close", "interstitial_close", "close_button",
            "closebutton", "ad_close_button", "skip_button", "skipbutton"
    };

    private static final Set<String> AD_CTA_LABELS = new HashSet<>(Arrays.asList(
            "go to google play", "get it on google play", "install", "install now",
            "download", "download now", "learn more", "play now", "watch now",
            "shop now", "get app", "open app", "visit site", "try now",
            "continue to app", "view app", "get offer", "apply now",
            "tai xuong", "cai dat", "cai dat ngay", "tim hieu them", "xem them"
    ));

    private static final String[] BLOCKED_PACKAGE_PREFIXES = {
            "com.mrtien.autoskip", "com.android.settings", "com.android.systemui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.vending", "com.google.android.inputmethod", "com.android.inputmethod",
            "com.android.keyguard"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long scanBurstUntil;
    private boolean scanScheduled;
    private long lastBlindAdTapAt;
    private int blindTapVariant;
    private String lastForegroundPackage = "";

    private WindowManager windowManager;

    private TextView pasteOverlay;
    private AccessibilityNodeInfo pasteTarget;
    private String pasteTargetSignature = "";
    private final Runnable hidePasteRunnable = this::hidePasteOverlay;

    private View tapCaptureOverlay;
    private AccessibilityNodeInfo focusedEditable;
    private String focusedEditableSignature = "";
    private Rect focusedEditableBounds = new Rect();
    private long firstCapturedTapAt;
    private float firstCapturedTapX;
    private float firstCapturedTapY;
    private float captureDownX;
    private float captureDownY;
    private Runnable pendingSingleTapReplay;
    private final Runnable hideCaptureRunnable = this::hideTapCaptureOverlay;

    private long lastAccessibilityTapAt;
    private String lastAccessibilityTapSignature = "";

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scanScheduled = false;
            if (!isAutoSkipEnabled()) return;
            long now = SystemClock.elapsedRealtime();
            if (now > scanBurstUntil) return;

            boolean acted = scanAndSkipOnce();
            if (acted) {
                // Keep scanning long enough for a second/third ad layer to appear.
                scanBurstUntil = Math.max(scanBurstUntil, now + AD_SCAN_BURST_MS);
            }
            scheduleNextScan();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        startScanBurst(AD_SCAN_BURST_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkgCs = event.getPackageName();
        String packageName = pkgCs == null ? "" : pkgCs.toString();
        if (!packageName.isEmpty()) {
            lastForegroundPackage = packageName;
        }

        int type = event.getEventType();

        if (isAutoSkipEnabled() && isSafePackage(packageName)) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                startScanBurst(AD_SCAN_BURST_MS);
            } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                startScanBurst(12000L);
            }
        }

        if (isDoubleTapPasteEnabled() && isSafePackage(packageName)) {
            if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                    || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                AccessibilityNodeInfo editable = resolveEditableForEvent(event);
                if (editable != null) {
                    rememberFocusedEditable(editable, packageName);

                    if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                        handleAccessibilityTap(editable, packageName);
                    } else if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                        // Android often emits this event after a real double-tap on text.
                        // Showing DÁN here is a robust fallback for OEM launchers/WebViews.
                        showPasteOverlay(editable, nodeSignature(editable, packageName));
                    }
                }
            }
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.removeCallbacks(hidePasteRunnable);
            hidePasteOverlay();
            hideTapCaptureOverlay();
            clearFocusedEditable();
        }
    }

    @Override
    public void onInterrupt() {
        hidePasteOverlay();
        hideTapCaptureOverlay();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hidePasteOverlay();
        hideTapCaptureOverlay();
        clearFocusedEditable();
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
                    if (root != null && scanRootForAd(root)) {
                        return true;
                    }
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

        AccessibilityNodeInfo strongCandidate = null;
        AccessibilityNodeInfo xCandidate = null;
        int adScore = 0;
        boolean hasWebView = false;
        boolean hasCta = false;
        boolean hasAdMarker = false;
        boolean hasCountdown = false;

        while (!queue.isEmpty() && visited < 1400) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node == null) continue;

            if (node.isVisibleToUser()) {
                String text = normalize(node.getText());
                String desc = normalize(node.getContentDescription());
                String hint = normalize(node.getHintText());
                String id = normalizeId(node.getViewIdResourceName());
                String cls = node.getClassName() == null
                        ? ""
                        : node.getClassName().toString().toLowerCase(Locale.ROOT);

                if (cls.contains("webview")) {
                    hasWebView = true;
                }

                if (strongCandidate == null
                        && (isStrongSkipLabel(text)
                        || isStrongSkipLabel(desc)
                        || isStrongSkipLabel(hint)
                        || hasStrongIdHint(id))) {
                    strongCandidate = node;
                }

                if (xCandidate == null && isXLike(node, text, desc)) {
                    xCandidate = node;
                }

                if (!hasCta && (isAdCta(text) || isAdCta(desc) || isAdCta(hint))) {
                    hasCta = true;
                }

                if (!hasAdMarker && (isAdMarker(text) || isAdMarker(desc) || isAdMarker(id))) {
                    hasAdMarker = true;
                }

                if (!hasCountdown && (isCountdown(text) || isCountdown(desc))) {
                    hasCountdown = true;
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        if (hasCta) adScore += 2;
        if (hasAdMarker) adScore += 2;
        if (hasCountdown) adScore += 2;
        if (hasWebView && hasCta) adScore += 1;

        if (strongCandidate != null && performClickOrTap(strongCandidate)) {
            incrementSkipCount();
            return true;
        }

        if (isCloseXEnabled() && xCandidate != null && adScore >= 1
                && performClickOrTap(xCandidate)) {
            incrementSkipCount();
            return true;
        }

        // Some ad SDKs draw the X on Canvas/WebView and expose no Accessibility node at all.
        // Only use a coordinate tap after the screen itself looks strongly like an ad.
        if (isCloseXEnabled() && adScore >= 2) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastBlindAdTapAt >= BLIND_AD_TAP_GAP_MS) {
                lastBlindAdTapAt = now;
                if (tapLikelyTopRightAdClose(root)) {
                    return true;
                }
            }
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
        return value.matches("close\\s*\\d+[a-z]*");
    }

    private boolean hasStrongIdHint(String id) {
        if (id.isEmpty()) return false;
        for (String hint : ID_HINTS) {
            if (id.contains(hint)) return true;
        }
        return false;
    }

    private boolean isAdCta(String value) {
        if (value.isEmpty()) return false;
        if (AD_CTA_LABELS.contains(value)) return true;
        return value.startsWith("go to google play")
                || value.startsWith("install now")
                || value.startsWith("download now")
                || value.startsWith("learn more")
                || value.startsWith("play now")
                || value.startsWith("shop now")
                || value.startsWith("get app");
    }

    private boolean isAdMarker(String value) {
        if (value.isEmpty()) return false;
        return "ad".equals(value)
                || "ads".equals(value)
                || "advertisement".equals(value)
                || "sponsored".equals(value)
                || "quang cao".equals(value)
                || "qc".equals(value)
                || value.contains("ad_container")
                || value.contains("adcontainer")
                || value.contains("interstitial")
                || value.contains("rewarded_ad")
                || value.contains("rewardedad");
    }

    private boolean isCountdown(String value) {
        if (value.isEmpty()) return false;
        return value.matches("\\d{1,2}\\s*s")
                || value.matches("\\d{1,2}\\s*sec")
                || value.matches("\\d{1,2}\\s*seconds");
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

        boolean nearTop = r.centerY() < screenH * 0.34f;
        boolean nearSide = r.centerX() > screenW * 0.58f || r.centerX() < screenW * 0.42f;
        boolean reasonablySmall = r.width() < screenW * 0.32f
                && r.height() < screenH * 0.20f;
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
        if (!r.isEmpty()) {
            return dispatchTap(r.centerX(), r.centerY(), false);
        }
        return false;
    }

    private boolean tapLikelyTopRightAdClose(AccessibilityNodeInfo root) {
        Rect r = new Rect();
        root.getBoundsInScreen(r);
        if (r.isEmpty()) {
            r.set(0, 0,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        }

        // Cycle through a few common ad-close positions, centered around the position
        // visible in the user's Honor Magic V3 recording.
        float[][] points = new float[][] {
                {0.915f, 0.085f},
                {0.945f, 0.080f},
                {0.905f, 0.115f},
                {0.955f, 0.120f}
        };
        float[] p = points[blindTapVariant % points.length];
        blindTapVariant++;

        float x = r.left + r.width() * p[0];
        float y = r.top + r.height() * p[1];
        return dispatchTap(x, y, false);
    }

    private boolean dispatchTap(float x, float y, boolean rearmPasteCapture) {
        hideTapCaptureOverlay();

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 55L);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        try {
            return dispatchGesture(
                    gesture,
                    rearmPasteCapture ? new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            super.onCompleted(gestureDescription);
                            handler.postDelayed(
                                    AdPasteAccessibilityService.this::rearmCaptureFromFocusedInput,
                                    100L
                            );
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            super.onCancelled(gestureDescription);
                            handler.postDelayed(
                                    AdPasteAccessibilityService.this::rearmCaptureFromFocusedInput,
                                    100L
                            );
                        }
                    } : null,
                    null
            );
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
        if (isEditableNode(source)) {
            return source;
        }

        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (isEditableNode(focused)) {
                    return focused;
                }
            }
        } catch (Throwable ignored) {
        }

        return findFocusedEditableInWindows();
    }

    private AccessibilityNodeInfo findFocusedEditableInWindows() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return null;
            for (int i = windows.size() - 1; i >= 0; i--) {
                AccessibilityWindowInfo window = windows.get(i);
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (isEditableNode(focused)) return focused;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void rememberFocusedEditable(AccessibilityNodeInfo node, String packageName) {
        if (!isEditableNode(node) || node.isPassword()) return;

        String signature = nodeSignature(node, packageName);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return;

        if (!signature.equals(focusedEditableSignature)) {
            clearFocusedEditable();
            try {
                focusedEditable = AccessibilityNodeInfo.obtain(node);
            } catch (Throwable ignored) {
                focusedEditable = node;
            }
            focusedEditableSignature = signature;
            focusedEditableBounds.set(bounds);
        } else {
            focusedEditableBounds.set(bounds);
        }

        armTapCaptureOverlay();
    }

    private void handleAccessibilityTap(AccessibilityNodeInfo editable, String packageName) {
        String signature = nodeSignature(editable, packageName);
        long now = SystemClock.elapsedRealtime();
        long delta = now - lastAccessibilityTapAt;

        if (signature.equals(lastAccessibilityTapSignature)
                && delta >= DOUBLE_TAP_MIN_MS
                && delta <= DOUBLE_TAP_MAX_MS) {
            showPasteOverlay(editable, signature);
            lastAccessibilityTapAt = 0L;
            lastAccessibilityTapSignature = "";
        } else {
            lastAccessibilityTapAt = now;
            lastAccessibilityTapSignature = signature;
        }
    }

    private void armTapCaptureOverlay() {
        if (!isDoubleTapPasteEnabled() || windowManager == null) return;
        if (pasteOverlay != null) return;
        if (focusedEditable == null || focusedEditableBounds.isEmpty()) return;
        if (tapCaptureOverlay != null) return;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        Rect b = new Rect(focusedEditableBounds);
        b.intersect(0, 0, screenW, screenH);
        if (b.isEmpty() || b.width() < dp(60) || b.height() < dp(24)) return;

        View capture = new View(this);
        capture.setBackgroundColor(Color.TRANSPARENT);
        capture.setOnTouchListener((v, event) -> handleCaptureTouch(event));
        tapCaptureOverlay = capture;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                b.width(),
                b.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = b.left;
        lp.y = b.top;

        try {
            windowManager.addView(capture, lp);
            handler.removeCallbacks(hideCaptureRunnable);
            handler.postDelayed(hideCaptureRunnable, CAPTURE_TIMEOUT_MS);
        } catch (Throwable t) {
            tapCaptureOverlay = null;
        }
    }

    private boolean handleCaptureTouch(MotionEvent event) {
        if (event == null) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                captureDownX = event.getRawX();
                captureDownY = event.getRawY();
                return true;

            case MotionEvent.ACTION_UP:
                float upX = event.getRawX();
                float upY = event.getRawY();
                float dx = upX - captureDownX;
                float dy = upY - captureDownY;
                float distanceSq = dx * dx + dy * dy;
                float maxMove = dp(18);

                if (distanceSq > maxMove * maxMove) {
                    // Do not trap selection/drag gestures. Release overlay and let the next
                    // interaction work normally.
                    hideTapCaptureOverlay();
                    handler.postDelayed(this::rearmCaptureFromFocusedInput, 650L);
                    return true;
                }

                long now = SystemClock.elapsedRealtime();
                long delta = now - firstCapturedTapAt;

                if (firstCapturedTapAt > 0L
                        && delta >= DOUBLE_TAP_MIN_MS
                        && delta <= DOUBLE_TAP_MAX_MS) {
                    if (pendingSingleTapReplay != null) {
                        handler.removeCallbacks(pendingSingleTapReplay);
                        pendingSingleTapReplay = null;
                    }
                    firstCapturedTapAt = 0L;
                    showPasteOverlayFromFocusedInput();
                    return true;
                }

                firstCapturedTapAt = now;
                firstCapturedTapX = upX;
                firstCapturedTapY = upY;

                if (pendingSingleTapReplay != null) {
                    handler.removeCallbacks(pendingSingleTapReplay);
                }
                pendingSingleTapReplay = () -> {
                    pendingSingleTapReplay = null;
                    firstCapturedTapAt = 0L;
                    dispatchTap(firstCapturedTapX, firstCapturedTapY, true);
                };
                handler.postDelayed(pendingSingleTapReplay, DOUBLE_TAP_MAX_MS + 25L);
                return true;

            case MotionEvent.ACTION_CANCEL:
                firstCapturedTapAt = 0L;
                return true;

            default:
                return true;
        }
    }

    private void rearmCaptureFromFocusedInput() {
        if (!isDoubleTapPasteEnabled()) return;
        AccessibilityNodeInfo focused = findFocusedEditableInWindows();
        if (focused == null) return;
        String pkg = focused.getPackageName() == null
                ? lastForegroundPackage
                : focused.getPackageName().toString();
        if (!isSafePackage(pkg)) return;
        rememberFocusedEditable(focused, pkg);
    }

    private void showPasteOverlayFromFocusedInput() {
        AccessibilityNodeInfo target = findFocusedEditableInWindows();
        if (target == null) {
            target = focusedEditable;
        }
        if (target == null) return;
        String pkg = target.getPackageName() == null
                ? lastForegroundPackage
                : target.getPackageName().toString();
        showPasteOverlay(target, nodeSignature(target, pkg));
    }

    private boolean isEditableNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return false;
        if (node.isPassword()) return false;
        if (node.isEditable()) return true;

        CharSequence cls = node.getClassName();
        if (cls != null) {
            String c = cls.toString().toLowerCase(Locale.ROOT);
            if (c.contains("edittext")
                    || c.contains("textfield")
                    || c.contains("searchview")) {
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

    private void showPasteOverlay(AccessibilityNodeInfo target, String signature) {
        if (windowManager == null || target == null) return;

        hideTapCaptureOverlay();
        hidePasteOverlay();

        try {
            pasteTarget = AccessibilityNodeInfo.obtain(target);
        } catch (Throwable t) {
            pasteTarget = target;
        }
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
            handler.postDelayed(hidePasteRunnable, OVERLAY_TIMEOUT_MS);
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
                target = reacquired;
                try {
                    reacquired.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    success = reacquired.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                } catch (Throwable ignored) {
                }
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
            Toast.makeText(
                    this,
                    "Ô này không cho Android thực hiện Dán tự động.",
                    Toast.LENGTH_SHORT
            ).show();
        }

        hidePasteOverlay();
        handler.postDelayed(this::rearmCaptureFromFocusedInput, 180L);
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
                int t = start;
                start = end;
                end = t;
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

    private AccessibilityNodeInfo findEditableBySignature(String signature) {
        if (TextUtils.isEmpty(signature)) return null;

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (int i = windows.size() - 1; i >= 0; i--) {
                    AccessibilityWindowInfo window = windows.get(i);
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    AccessibilityNodeInfo found = findEditableBySignature(root, signature);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {
        }

        return findEditableBySignature(getRootInActiveWindow(), signature);
    }

    private AccessibilityNodeInfo findEditableBySignature(
            AccessibilityNodeInfo root,
            String signature
    ) {
        if (root == null) return null;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < 1000) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node == null) continue;

            String pkg = node.getPackageName() == null
                    ? ""
                    : node.getPackageName().toString();
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

    private void hideTapCaptureOverlay() {
        handler.removeCallbacks(hideCaptureRunnable);
        if (tapCaptureOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(tapCaptureOverlay);
            } catch (Throwable ignored) {
            }
        }
        tapCaptureOverlay = null;
        firstCapturedTapAt = 0L;
        if (pendingSingleTapReplay != null) {
            handler.removeCallbacks(pendingSingleTapReplay);
            pendingSingleTapReplay = null;
        }
    }

    private void clearFocusedEditable() {
        focusedEditable = null;
        focusedEditableSignature = "";
        focusedEditableBounds.setEmpty();
        lastAccessibilityTapAt = 0L;
        lastAccessibilityTapSignature = "";
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
