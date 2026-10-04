package com.mrtien.autoskip;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * v2.1: Paste-only Accessibility service.
 *
 * Important design rule: this service NEVER requests Touch Exploration and NEVER
 * intercepts raw touches. That keeps the keyboard, scrolling and normal taps fully
 * controlled by Android/the foreground app.
 *
 * A double tap is inferred only from normal Accessibility events:
 *  - two TYPE_VIEW_CLICKED events on the same editable within the time window, or
 *  - two collapsed cursor-selection events within the window, or
 *  - a non-empty text selection (the normal result of double-tapping a word).
 */
public class AdPasteAccessibilityService extends AccessibilityService {

    private static final long DOUBLE_TAP_MIN_MS = 70L;
    private static final long DOUBLE_TAP_MAX_MS = 700L;
    private static final long TEXT_CHANGE_GUARD_MS = 430L;
    private static final long PASTE_BUTTON_TIMEOUT_MS = 3600L;

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
            "com.hihonor.android.launcher"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private String lastForegroundPackage = "";

    private long lastTextChangedAt;

    private long lastClickAt;
    private String lastClickSignature = "";

    private long lastSelectionAt;
    private String lastSelectionSignature = "";
    private int lastSelectionStart = -1;
    private int lastSelectionEnd = -1;

    private TextView pasteOverlay;
    private AccessibilityNodeInfo pasteTarget;
    private String pasteTargetSignature = "";
    private final Runnable hidePasteRunnable = this::hidePasteOverlay;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        resetGestureHistory();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isDoubleTapPasteEnabled()) return;

        String packageName = event.getPackageName() == null
                ? ""
                : event.getPackageName().toString();
        if (!packageName.isEmpty()) lastForegroundPackage = packageName;
        if (!isSafePackage(packageName)) return;

        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            hidePasteOverlay();
            resetGestureHistory();
            return;
        }

        AccessibilityNodeInfo editable = resolveEditableForEvent(event);

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            lastTextChangedAt = SystemClock.elapsedRealtime();
            hidePasteOverlay();
            // Typing must never count as a tap sequence.
            resetGestureHistory();
            return;
        }

        if (editable == null || editable.isPassword()) return;
        String signature = nodeSignature(editable, packageName);

        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleClickEvent(editable, signature);
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            handleSelectionEvent(editable, signature);
        }
    }

    private void handleClickEvent(AccessibilityNodeInfo editable, String signature) {
        long now = SystemClock.elapsedRealtime();
        long delta = now - lastClickAt;

        if (signature.equals(lastClickSignature)
                && delta >= DOUBLE_TAP_MIN_MS
                && delta <= DOUBLE_TAP_MAX_MS) {
            resetClickHistory();
            showPasteOverlay(editable, signature);
        } else {
            lastClickAt = now;
            lastClickSignature = signature;
        }
    }

    private void handleSelectionEvent(AccessibilityNodeInfo editable, String signature) {
        long now = SystemClock.elapsedRealtime();

        // Honor/MagicOS sends SELECTION_CHANGED after every typed character.
        // Ignore that entire window so typing can never summon DÁN.
        if (now - lastTextChangedAt < TEXT_CHANGE_GUARD_MS) {
            return;
        }

        int start = editable.getTextSelectionStart();
        int end = editable.getTextSelectionEnd();
        if (start < 0 || end < 0) return;

        // Android's standard double-tap on an existing word selects a range.
        // This is the cleanest signal and does not steal any touch from the keyboard.
        if (start != end) {
            resetSelectionHistory();
            showPasteOverlay(editable, signature);
            return;
        }

        long delta = now - lastSelectionAt;
        boolean sameNode = signature.equals(lastSelectionSignature);
        boolean sameCursor = start == lastSelectionStart && end == lastSelectionEnd;

        // Empty fields and cursor-only inputs often emit two collapsed selection events
        // rather than VIEW_CLICKED. Require two events from the same field/cursor.
        if (sameNode && sameCursor
                && delta >= DOUBLE_TAP_MIN_MS
                && delta <= DOUBLE_TAP_MAX_MS) {
            resetSelectionHistory();
            showPasteOverlay(editable, signature);
        } else {
            lastSelectionAt = now;
            lastSelectionSignature = signature;
            lastSelectionStart = start;
            lastSelectionEnd = end;
        }
    }

    private AccessibilityNodeInfo resolveEditableForEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        AccessibilityNodeInfo editable = findEditableAncestor(source);
        if (editable != null) return editable;

        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (isEditableNode(focused)) return focused;
            }
        } catch (Throwable ignored) {
        }

        return findFocusedEditableInWindows();
    }

    private AccessibilityNodeInfo findEditableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (isEditableNode(current)) return current;
            current = current.getParent();
        }
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
        return null;
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

    private String nodeSignature(AccessibilityNodeInfo node, String fallbackPackage) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        String pkg = node.getPackageName() == null
                ? fallbackPackage
                : node.getPackageName().toString();
        String id = node.getViewIdResourceName();
        String cls = node.getClassName() == null ? "" : node.getClassName().toString();
        return pkg + "|" + (id == null ? "" : id) + "|" + cls + "|" + r.flattenToString();
    }

    private void showPasteOverlay(AccessibilityNodeInfo target, String signature) {
        if (windowManager == null || target == null || !clipboardHasText()) return;

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
        button.setTextSize(15f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(15), dp(8), dp(15), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(11, 143, 106));
        bg.setCornerRadius(dp(11));
        bg.setStroke(dp(1), Color.argb(90, 255, 255, 255));
        button.setBackground(bg);
        button.setElevation(dp(8));
        button.setOnClickListener(v -> pasteIntoTarget());
        pasteOverlay = button;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int w = dp(76);
        int h = dp(46);

        // iOS-like placement: just above the input, aligned near its right side.
        int x = Math.max(dp(6), Math.min(bounds.right - w, screenW - w - dp(6)));
        int y;
        if (bounds.top >= h + dp(10)) {
            y = bounds.top - h - dp(7);
        } else {
            y = Math.min(bounds.bottom + dp(7), screenH - h - dp(6));
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
            handler.postDelayed(hidePasteRunnable, PASTE_BUTTON_TIMEOUT_MS);
        } catch (Throwable t) {
            pasteOverlay = null;
        }
    }

    private boolean clipboardHasText() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return false;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return false;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            return text != null && text.length() > 0;
        } catch (Throwable ignored) {
            return false;
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
            Toast.makeText(this, "Ô này không cho phép Dán tự động.", Toast.LENGTH_SHORT).show();
        }

        hidePasteOverlay();
        resetGestureHistory();
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
                    AccessibilityWindowInfo w = windows.get(i);
                    if (w == null) continue;
                    AccessibilityNodeInfo found = findEditableBySignature(w.getRoot(), signature);
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
        while (!queue.isEmpty() && visited < 900) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node == null) continue;
            String pkg = node.getPackageName() == null
                    ? lastForegroundPackage
                    : node.getPackageName().toString();
            if (isEditableNode(node) && signature.equals(nodeSignature(node, pkg))) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private void hidePasteOverlay() {
        handler.removeCallbacks(hidePasteRunnable);
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

    private void resetClickHistory() {
        lastClickAt = 0L;
        lastClickSignature = "";
    }

    private void resetSelectionHistory() {
        lastSelectionAt = 0L;
        lastSelectionSignature = "";
        lastSelectionStart = -1;
        lastSelectionEnd = -1;
    }

    private void resetGestureHistory() {
        resetClickHistory();
        resetSelectionHistory();
    }

    private boolean isDoubleTapPasteEnabled() {
        return prefs().getBoolean(MainActivity.KEY_DOUBLE_TAP_PASTE, true);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private boolean isSafePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        String lower = packageName.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_PACKAGE_PREFIXES) {
            if (lower.startsWith(blocked)) return false;
        }
        return true;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
}
