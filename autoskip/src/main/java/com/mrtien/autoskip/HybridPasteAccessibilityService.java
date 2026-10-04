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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * v2.2.3 hybrid detector.
 *
 * First tap remains completely native. After Android reports that first interaction,
 * a transparent catcher is armed over ONLY the focused edit field for a short time.
 * It catches the second tap, removes itself, then immediately re-injects that second
 * tap to the foreground app before showing the tiny paste pill. This is much more
 * reliable than trying to infer both taps from Accessibility events, while avoiding
 * the old problem where an always-on overlay swallowed the first tap and broke typing.
 */
public class HybridPasteAccessibilityService extends AccessibilityService {

    private static final long ARM_TIMEOUT_MS = 950L;
    private static final long SAME_FIRST_EVENT_MS = 170L;
    private static final long TEXT_CHANGE_GUARD_MS = 220L;
    private static final long MENU_TIMEOUT_MS = 10000L;
    private static final long LONG_PRESS_MS = 470L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private ClipboardManager clipboard;

    private View secondTapCatcher;
    private WindowManager.LayoutParams catcherLp;
    private final Rect catcherBounds = new Rect();
    private String catcherSignature = "";
    private String catcherPackage = "";
    private AccessibilityNodeInfo catcherTarget;
    private long catcherArmedAt;

    private float downX;
    private float downY;
    private long downAt;
    private boolean moved;

    private long lastFirstSignalAt;
    private String lastFirstSignalSignature = "";
    private long lastTextChangedAt;

    private LinearLayout menu;
    private LinearLayout historyPanel;
    private AccessibilityNodeInfo target;
    private String targetSignature = "";
    private final Rect targetBounds = new Rect();

    private String lastSelectedText = "";
    private long lastSelectedAt;

    private final Runnable disarmRunnable = this::removeSecondTapCatcher;
    private final Runnable hideMenuRunnable = this::hideAllMenus;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            () -> handler.postDelayed(this::captureClipboard, 100L);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            try { clipboard.addPrimaryClipChangedListener(clipListener); } catch (Throwable ignored) {}
        }
        if (ShizukuClipboardBridge.hasPermission()) ShizukuClipboardBridge.start(this);
        resetFirstSignal();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            rememberSelectedText(event.getSource());
        }
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String label = nodeLabel(event.getSource());
            if (isCopyLabel(label)) {
                if (!lastSelectedText.isEmpty()
                        && SystemClock.elapsedRealtime() - lastSelectedAt < 10000L) {
                    ClipboardHistoryStore.addText(this, lastSelectedText);
                }
                handler.postDelayed(this::captureClipboard, 120L);
            }
        }

        if (!enabled()) {
            removeSecondTapCatcher();
            hideAllMenus();
            resetFirstSignal();
            return;
        }

        // Our own overlay window events must not close the menu or re-arm the catcher.
        if (menu != null || historyPanel != null) return;
        if (getPackageName().equals(pkg)) return;

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            lastTextChangedAt = SystemClock.elapsedRealtime();
            removeSecondTapCatcher();
            resetFirstSignal();
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            removeSecondTapCatcher();
            resetFirstSignal();
            return;
        }

        if (type != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return;
        }

        AccessibilityNodeInfo editable = resolveEditable(event);
        if (!isEditable(editable)) return;
        String epkg = editable.getPackageName() == null ? pkg : editable.getPackageName().toString();
        if (getPackageName().equals(epkg)) return;
        String sig = signature(editable, epkg);

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            if (SystemClock.elapsedRealtime() - lastTextChangedAt < TEXT_CHANGE_GUARD_MS) return;
            int s = editable.getTextSelectionStart();
            int e = editable.getTextSelectionEnd();
            // Android already completed a native word selection. Do not touch selection;
            // just show the tiny menu next to the field.
            if (s >= 0 && e >= 0 && s != e) {
                removeSecondTapCatcher();
                resetFirstSignal();
                showMenu(editable, sig);
                return;
            }
        }

        armFromFirstNativeInteraction(editable, sig, epkg);
    }

    private void armFromFirstNativeInteraction(AccessibilityNodeInfo editable, String sig, String pkg) {
        long now = SystemClock.elapsedRealtime();

        // One real first tap can emit FOCUSED + CLICKED + SELECTION_CHANGED. Treat all
        // events inside this short window as one physical tap.
        if (sig.equals(lastFirstSignalSignature)
                && now - lastFirstSignalAt >= 0L
                && now - lastFirstSignalAt < SAME_FIRST_EVENT_MS) {
            return;
        }

        lastFirstSignalAt = now;
        lastFirstSignalSignature = sig;

        // If already armed for this exact field, keep the same second-tap window.
        if (secondTapCatcher != null && sig.equals(catcherSignature)) return;

        removeSecondTapCatcher();
        try { catcherTarget = AccessibilityNodeInfo.obtain(editable); }
        catch (Throwable t) { catcherTarget = editable; }
        catcherSignature = sig;
        catcherPackage = pkg == null ? "" : pkg;

        // Delay very slightly so the first physical ACTION_UP has definitely finished.
        handler.postDelayed(() -> installSecondTapCatcher(editable, sig, catcherPackage), 28L);
    }

    private void installSecondTapCatcher(AccessibilityNodeInfo editable, String sig, String pkg) {
        if (!enabled() || menu != null || historyPanel != null || wm == null) return;
        if (!sig.equals(catcherSignature)) return;

        AccessibilityNodeInfo current = findBySignature(sig);
        if (current == null) current = findFocusedEditable();
        if (!isEditable(current)) return;

        Rect b = new Rect();
        current.getBoundsInScreen(b);
        if (b.isEmpty() || b.width() < dp(18) || b.height() < dp(18)) return;

        catcherBounds.set(b);
        View v = new View(this);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setClickable(true);
        v.setOnTouchListener(this::onSecondTapTouch);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                b.width(), b.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = b.left;
        lp.y = b.top;

        secondTapCatcher = v;
        catcherLp = lp;
        catcherArmedAt = SystemClock.elapsedRealtime();
        try {
            wm.addView(v, lp);
            handler.removeCallbacks(disarmRunnable);
            handler.postDelayed(disarmRunnable, ARM_TIMEOUT_MS);
        } catch (Throwable t) {
            secondTapCatcher = null;
            catcherLp = null;
        }
    }

    private boolean onSecondTapTouch(View view, MotionEvent event) {
        if (event == null) return true;
        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                downAt = SystemClock.elapsedRealtime();
                moved = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (distanceSq(x, y, downX, downY) > dp(13) * dp(13)) moved = true;
                return true;

            case MotionEvent.ACTION_UP:
                long duration = SystemClock.elapsedRealtime() - downAt;
                String sig = catcherSignature;
                AccessibilityNodeInfo saved = catcherTarget;
                String pkg = catcherPackage;

                // Remove BEFORE re-injecting so the foreground app receives the gesture.
                removeSecondTapCatcherKeepingTarget();

                if (moved) {
                    forwardSwipe(downX, downY, x, y,
                            Math.max(120L, Math.min(duration, 650L)));
                    clearCatcherIdentity();
                    return true;
                }

                if (duration >= LONG_PRESS_MS) {
                    forwardLongPress(x, y, Math.max(520L, Math.min(duration, 900L)));
                    clearCatcherIdentity();
                    return true;
                }

                forwardSecondTapAndShow(x, y, saved, sig, pkg);
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeSecondTapCatcher();
                return true;
        }
        return true;
    }

    private void forwardSecondTapAndShow(float x, float y,
                                         AccessibilityNodeInfo saved,
                                         String sig,
                                         String pkg) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0L, 42L));

        Runnable show = () -> {
            AccessibilityNodeInfo editable = findBySignature(sig);
            if (editable == null) editable = findFocusedEditable();
            if (editable == null) editable = saved;
            if (isEditableLoose(editable)) showMenu(editable, sig);
            clearCatcherIdentity();
        };

        boolean dispatched = false;
        try {
            dispatched = dispatchGesture(b.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    handler.postDelayed(show, 55L);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    handler.postDelayed(show, 25L);
                }
            }, null);
        } catch (Throwable ignored) {}

        if (!dispatched) handler.postDelayed(show, 35L);
    }

    private void forwardSwipe(float sx, float sy, float ex, float ey, long duration) {
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0L, duration));
        try { dispatchGesture(b.build(), null, null); } catch (Throwable ignored) {}
    }

    private void forwardLongPress(float x, float y, long duration) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0L, duration));
        try { dispatchGesture(b.build(), null, null); } catch (Throwable ignored) {}
    }

    private void showMenu(AccessibilityNodeInfo editable, String sig) {
        if (wm == null || editable == null || menu != null) return;
        removeSecondTapCatcher();
        hideHistoryOnly();
        captureClipboard();

        try { target = AccessibilityNodeInfo.obtain(editable); }
        catch (Throwable t) { target = editable; }
        targetSignature = sig;
        editable.getBoundsInScreen(targetBounds);
        if (targetBounds.isEmpty()) return;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(0, 0, 0, 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(35, 35, 35));
        bg.setCornerRadius(dp(6));
        bar.setBackground(bg);
        bar.setElevation(dp(4));

        TextView paste = menuItem("DÁN", 9.5f);
        paste.setOnClickListener(v -> pasteLatest());
        bar.addView(paste, new LinearLayout.LayoutParams(dp(34), dp(22)));

        TextView hist = menuItem("▤", 11.5f);
        hist.setContentDescription("Lịch sử sao chép");
        hist.setOnClickListener(v -> showHistory());
        bar.addView(hist, new LinearLayout.LayoutParams(dp(20), dp(22)));

        int menuW = dp(56);
        int menuH = dp(24);
        int[] pos = position(targetBounds, menuW, menuH);
        menu = bar;
        try {
            wm.addView(bar, overlayParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    pos[0], pos[1]));
            handler.removeCallbacks(hideMenuRunnable);
            handler.postDelayed(hideMenuRunnable, MENU_TIMEOUT_MS);
        } catch (Throwable t) {
            menu = null;
        }
    }

    private TextView menuItem(String text, float sp) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(1), 0, dp(1), 0);
        return v;
    }

    private void pasteLatest() {
        handler.removeCallbacks(hideMenuRunnable);
        boolean ok = pasteToTarget();
        if (ok) incrementCount();
        else Toast.makeText(this, "Không dán được vào ô này.", Toast.LENGTH_SHORT).show();
        hideAllMenus();
        resetFirstSignal();
    }

    private void showHistory() {
        captureClipboard();
        List<ClipboardHistoryStore.Item> items = ClipboardHistoryStore.load(this);
        if (items.isEmpty()) {
            Toast.makeText(this, "Chưa có lịch sử sao chép.", Toast.LENGTH_SHORT).show();
            return;
        }
        hideHistoryOnly();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(40, 40, 40));
        bg.setCornerRadius(dp(12));
        root.setBackground(bg);
        root.setElevation(dp(8));

        TextView title = menuItem("LỊCH SỬ   ✕", 12f);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setOnClickListener(v -> hideHistoryOnly());
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        int n = Math.min(items.size(), 30);
        for (int i = 0; i < n; i++) {
            ClipboardHistoryStore.Item ci = items.get(i);
            TextView row = menuItem(ci.preview(), 13f);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(8), dp(9), dp(8));
            row.setOnClickListener(v -> pasteHistory(ci));
            list.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int w = Math.min(dp(330), sw - dp(18));
        int h = dp(280);
        int x = Math.max(dp(9), Math.min(targetBounds.left, sw - w - dp(9)));
        int y = targetBounds.top - h - dp(7);
        if (y < dp(8)) y = Math.min(targetBounds.bottom + dp(7), sh - h - dp(8));

        historyPanel = root;
        try {
            wm.addView(root, overlayParams(w, h, x, y));
            handler.removeCallbacks(hideMenuRunnable);
            handler.postDelayed(hideMenuRunnable, 12000L);
        } catch (Throwable t) {
            historyPanel = null;
        }
    }

    private void pasteHistory(ClipboardHistoryStore.Item item) {
        if (clipboard == null) return;
        ClipData cd = ClipboardHistoryStore.toClipData(this, item);
        if (cd == null) return;
        try { clipboard.setPrimaryClip(cd); }
        catch (Throwable t) {
            Toast.makeText(this, "Mục này không còn truy cập được.", Toast.LENGTH_SHORT).show();
            return;
        }
        hideHistoryOnly();
        handler.postDelayed(() -> {
            if (pasteToTarget()) incrementCount();
            else Toast.makeText(this, "Không dán được mục đã chọn.", Toast.LENGTH_SHORT).show();
            hideAllMenus();
        }, 100L);
    }

    private boolean pasteToTarget() {
        AccessibilityNodeInfo node = target;
        if (node == null || !isEditableLoose(node)) node = findBySignature(targetSignature);
        if (node == null) node = findFocusedEditable();
        if (node == null) return false;

        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true;
        } catch (Throwable ignored) {}
        return setTextFallback(node);
    }

    private boolean setTextFallback(AccessibilityNodeInfo node) {
        if (clipboard == null || node == null) return false;
        try {
            ClipData cd = clipboard.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return false;
            CharSequence ins = cd.getItemAt(0).coerceToText(this);
            if (ins == null) return false;

            String old = node.getText() == null ? "" : node.getText().toString();
            int s = node.getTextSelectionStart();
            int e = node.getTextSelectionEnd();
            if (s < 0 || s > old.length()) s = old.length();
            if (e < 0 || e > old.length()) e = s;
            if (e < s) { int t = s; s = e; e = t; }

            String value = old.substring(0, s) + ins + old.substring(e);
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private AccessibilityNodeInfo resolveEditable(AccessibilityEvent event) {
        AccessibilityNodeInfo n = event.getSource();
        for (int i = 0; n != null && i < 7; i++) {
            if (isEditable(n)) return n;
            n = n.getParent();
        }
        return findFocusedEditable();
    }

    private AccessibilityNodeInfo findFocusedEditable() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (int i = ws.size() - 1; i >= 0; i--) {
                    AccessibilityWindowInfo w = ws.get(i);
                    if (w == null || w.getRoot() == null) continue;
                    AccessibilityNodeInfo n = w.getRoot().findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (isEditable(n)) return n;
                }
            }
        } catch (Throwable ignored) {}

        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo n = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (isEditable(n)) return n;
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isEditable(AccessibilityNodeInfo n) {
        if (n == null || !n.isVisibleToUser() || !n.isEnabled() || n.isPassword()) return false;
        if (n.isEditable() && n.isFocused()) return true;
        CharSequence c = n.getClassName();
        if (c != null && n.isFocused()) {
            String s = c.toString().toLowerCase(Locale.ROOT);
            return s.contains("edittext") || s.contains("textfield") || s.contains("searchview");
        }
        return false;
    }

    private boolean isEditableLoose(AccessibilityNodeInfo n) {
        if (n == null || !n.isVisibleToUser() || !n.isEnabled() || n.isPassword()) return false;
        if (n.isEditable()) return true;
        CharSequence c = n.getClassName();
        if (c != null) {
            String s = c.toString().toLowerCase(Locale.ROOT);
            return s.contains("edittext") || s.contains("textfield") || s.contains("searchview");
        }
        return false;
    }

    private String signature(AccessibilityNodeInfo n, String pkg) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        String id = n.getViewIdResourceName();
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        return pkg + "|" + (id == null ? "" : id) + "|" + cls + "|" + r.flattenToString();
    }

    private AccessibilityNodeInfo findBySignature(String sig) {
        if (sig == null || sig.isEmpty()) return null;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    AccessibilityNodeInfo f = bfs(w.getRoot(), sig);
                    if (f != null) return f;
                }
            }
        } catch (Throwable ignored) {}
        return bfs(getRootInActiveWindow(), sig);
    }

    private AccessibilityNodeInfo bfs(AccessibilityNodeInfo root, String sig) {
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int visited = 0;
        while (!q.isEmpty() && visited++ < 1000) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n == null) continue;
            String p = n.getPackageName() == null ? catcherPackage : n.getPackageName().toString();
            if (isEditableLoose(n) && sig.equals(signature(n, p))) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.addLast(child);
            }
        }
        return null;
    }

    private void rememberSelectedText(AccessibilityNodeInfo node) {
        if (node == null || node.getText() == null) return;
        try {
            int s = node.getTextSelectionStart();
            int e = node.getTextSelectionEnd();
            String t = node.getText().toString();
            if (s >= 0 && e > s && e <= t.length()) {
                lastSelectedText = t.substring(s, e);
                lastSelectedAt = SystemClock.elapsedRealtime();
            }
        } catch (Throwable ignored) {}
    }

    private String nodeLabel(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
    }

    private boolean isCopyLabel(String s) {
        return "copy".equals(s) || "sao chép".equals(s) || "sao chep".equals(s);
    }

    private void captureClipboard() {
        ClipData cd = null;
        if (ShizukuClipboardBridge.hasPermission()) {
            if (!ShizukuClipboardBridge.isRunning()) ShizukuClipboardBridge.start(this);
            cd = ShizukuClipboardBridge.readNow();
        }
        if (cd == null && clipboard != null) {
            try { cd = clipboard.getPrimaryClip(); } catch (Throwable ignored) {}
        }
        if (cd != null) ClipboardHistoryStore.addClip(this, cd);
    }

    private int[] position(Rect b, int w, int h) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int x = Math.max(dp(4), Math.min(b.right - w, sw - w - dp(4)));
        int y = b.top - h - dp(4);
        if (y < dp(4)) y = Math.min(b.bottom + dp(4), sh - h - dp(4));
        return new int[]{x, y};
    }

    private WindowManager.LayoutParams overlayParams(int w, int h, int x, int y) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        return lp;
    }

    private void removeSecondTapCatcher() {
        removeSecondTapCatcherKeepingTarget();
        clearCatcherIdentity();
    }

    private void removeSecondTapCatcherKeepingTarget() {
        handler.removeCallbacks(disarmRunnable);
        if (secondTapCatcher != null && wm != null) {
            try { wm.removeView(secondTapCatcher); } catch (Throwable ignored) {}
        }
        secondTapCatcher = null;
        catcherLp = null;
        catcherBounds.setEmpty();
    }

    private void clearCatcherIdentity() {
        catcherSignature = "";
        catcherPackage = "";
        catcherTarget = null;
        catcherArmedAt = 0L;
    }

    private void hideAllMenus() {
        handler.removeCallbacks(hideMenuRunnable);
        if (menu != null && wm != null) {
            try { wm.removeView(menu); } catch (Throwable ignored) {}
        }
        menu = null;
        hideHistoryOnly();
    }

    private void hideHistoryOnly() {
        if (historyPanel != null && wm != null) {
            try { wm.removeView(historyPanel); } catch (Throwable ignored) {}
        }
        historyPanel = null;
    }

    private void resetFirstSignal() {
        lastFirstSignalAt = 0L;
        lastFirstSignalSignature = "";
    }

    private float distanceSq(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private void incrementCount() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        long c = p.getLong(MainActivity.KEY_PASTE_COUNT, 0L);
        p.edit().putLong(MainActivity.KEY_PASTE_COUNT, c + 1L).apply();
    }

    private boolean enabled() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_DOUBLE_TAP_PASTE, true);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onInterrupt() {
        removeSecondTapCatcher();
        hideAllMenus();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeSecondTapCatcher();
        hideAllMenus();
        if (clipboard != null) {
            try { clipboard.removePrimaryClipChangedListener(clipListener); } catch (Throwable ignored) {}
        }
        ShizukuClipboardBridge.stop();
        super.onDestroy();
    }
}
