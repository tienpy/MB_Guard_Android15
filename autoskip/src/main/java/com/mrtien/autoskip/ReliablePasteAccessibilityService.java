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

public class ReliablePasteAccessibilityService extends AccessibilityService {

    private static final int MODE_NONE = 0;
    private static final int MODE_FIRST = 1;
    private static final int MODE_SECOND = 2;

    private static final long SECOND_WINDOW_MS = 900L;
    private static final long FIRST_WATCH_MS = 15000L;
    private static final long SAME_NATIVE_EVENT_MS = 170L;
    private static final long LONG_PRESS_MS = 470L;
    private static final long MENU_TIMEOUT_MS = 10000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private ClipboardManager clipboard;

    private View catcher;
    private int catcherMode = MODE_NONE;
    private String catcherSignature = "";
    private String catcherPackage = "";
    private AccessibilityNodeInfo catcherTarget;
    private final Rect catcherBounds = new Rect();

    private float downX;
    private float downY;
    private long downAt;
    private boolean moved;

    private long lastNativeSignalAt;
    private String lastNativeSignalSignature = "";
    private long suppressDetectionUntil;

    private LinearLayout menu;
    private LinearLayout historyPanel;
    private AccessibilityNodeInfo target;
    private String targetSignature = "";
    private final Rect targetBounds = new Rect();

    private String lastSelectedText = "";
    private long lastSelectedAt;

    private final Runnable catcherTimeout = this::onCatcherTimeout;
    private final Runnable menuTimeout = this::hideMenusOnly;
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
        resetNativeSignal();
        handler.postDelayed(this::armFirstIfFocused, 450L);
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
            removeCatcher();
            hideMenusOnly();
            resetNativeSignal();
            return;
        }

        if (getPackageName().equals(pkg)) return;

        long now = SystemClock.elapsedRealtime();
        if (now < suppressDetectionUntil) return;

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            removeCatcher();
            hideMenusOnly();
            resetNativeSignal();
            handler.postDelayed(this::armFirstIfFocused, 260L);
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (catcherMode == MODE_SECOND) {
                removeCatcher();
                handler.postDelayed(this::armFirstIfFocused, 90L);
            }
            resetNativeSignal();
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (catcherMode == MODE_FIRST) {
                handler.removeCallbacks(refreshFirstRunnable);
                handler.postDelayed(refreshFirstRunnable, 90L);
            }
            return;
        }

        if (type != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return;
        }

        if (catcherMode != MODE_NONE) return;

        AccessibilityNodeInfo editable = resolveEditable(event);
        if (!isEditable(editable)) return;
        String epkg = editable.getPackageName() == null ? pkg : editable.getPackageName().toString();
        if (getPackageName().equals(epkg)) return;
        String sig = signature(editable, epkg);

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            int s = editable.getTextSelectionStart();
            int e = editable.getTextSelectionEnd();
            if (s >= 0 && e >= 0 && s != e) {
                showMenu(editable, sig);
                return;
            }
        }

        if (sig.equals(lastNativeSignalSignature)
                && now - lastNativeSignalAt >= 0
                && now - lastNativeSignalAt < SAME_NATIVE_EVENT_MS) {
            return;
        }
        lastNativeSignalAt = now;
        lastNativeSignalSignature = sig;
        handler.postDelayed(() -> installCatcher(editable, sig, epkg, MODE_SECOND), 24L);
    }

    private final Runnable refreshFirstRunnable = () -> {
        if (catcherMode != MODE_FIRST) return;
        AccessibilityNodeInfo focused = findFocusedEditable();
        if (!isEditable(focused)) {
            removeCatcher();
            return;
        }
        String pkg = focused.getPackageName() == null ? "" : focused.getPackageName().toString();
        String sig = signature(focused, pkg);
        Rect b = new Rect();
        focused.getBoundsInScreen(b);
        if (!sig.equals(catcherSignature) || !b.equals(catcherBounds)) {
            removeCatcher();
            installCatcher(focused, sig, pkg, MODE_FIRST);
        }
    };

    private void armFirstIfFocused() {
        if (!enabled() || historyPanel != null) return;
        AccessibilityNodeInfo focused = findFocusedEditable();
        if (!isEditable(focused)) return;
        String pkg = focused.getPackageName() == null ? "" : focused.getPackageName().toString();
        if (getPackageName().equals(pkg)) return;
        installCatcher(focused, signature(focused, pkg), pkg, MODE_FIRST);
    }

    private void installCatcher(AccessibilityNodeInfo editable, String sig, String pkg, int mode) {
        if (!enabled() || wm == null || editable == null) return;
        if (historyPanel != null) return;

        AccessibilityNodeInfo current = findBySignature(sig);
        if (current == null) current = findFocusedEditable();
        if (!isEditableLoose(current)) return;

        Rect b = new Rect();
        current.getBoundsInScreen(b);
        if (b.isEmpty() || b.width() < dp(18) || b.height() < dp(18)) return;

        removeCatcher();
        try { catcherTarget = AccessibilityNodeInfo.obtain(current); }
        catch (Throwable t) { catcherTarget = current; }
        catcherSignature = sig;
        catcherPackage = pkg == null ? "" : pkg;
        catcherBounds.set(b);
        catcherMode = mode;

        View v = new View(this);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setClickable(true);
        v.setOnTouchListener(this::onCatcherTouch);
        catcher = v;

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

        try {
            wm.addView(v, lp);
            handler.removeCallbacks(catcherTimeout);
            handler.postDelayed(catcherTimeout,
                    mode == MODE_SECOND ? SECOND_WINDOW_MS : FIRST_WATCH_MS);
        } catch (Throwable t) {
            catcher = null;
            catcherMode = MODE_NONE;
        }
    }

    private boolean onCatcherTouch(View v, MotionEvent event) {
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
                int mode = catcherMode;
                String sig = catcherSignature;
                String pkg = catcherPackage;
                AccessibilityNodeInfo saved = catcherTarget;

                removeCatcher();
                suppressDetectionUntil = SystemClock.elapsedRealtime() + 180L;

                if (moved) {
                    hideMenusOnly();
                    dispatchSwipe(downX, downY, x, y,
                            Math.max(120L, Math.min(duration, 650L)),
                            () -> handler.postDelayed(this::armFirstIfFocused, 60L));
                    return true;
                }

                if (duration >= LONG_PRESS_MS) {
                    hideMenusOnly();
                    dispatchLongPress(x, y, Math.max(520L, Math.min(duration, 900L)),
                            () -> handler.postDelayed(this::armFirstIfFocused, 80L));
                    return true;
                }

                if (mode == MODE_FIRST) {
                    hideMenusOnly();
                    dispatchTap(x, y, () -> {
                        AccessibilityNodeInfo e = findBySignature(sig);
                        if (e == null) e = findFocusedEditable();
                        if (e == null) e = saved;
                        if (isEditableLoose(e)) {
                            final AccessibilityNodeInfo next = e;
                            handler.postDelayed(() -> installCatcher(next, sig, pkg, MODE_SECOND), 22L);
                        }
                    });
                } else {
                    dispatchTap(x, y, () -> {
                        AccessibilityNodeInfo e = findBySignature(sig);
                        if (e == null) e = findFocusedEditable();
                        if (e == null) e = saved;
                        if (isEditableLoose(e)) showMenu(e, sig);
                    });
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCatcher();
                handler.postDelayed(this::armFirstIfFocused, 80L);
                return true;
        }
        return true;
    }

    private void onCatcherTimeout() {
        int oldMode = catcherMode;
        removeCatcher();
        if (oldMode == MODE_SECOND || oldMode == MODE_FIRST) {
            handler.postDelayed(this::armFirstIfFocused, 70L);
        }
    }

    private void dispatchTap(float x, float y, Runnable after) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0L, 38L));
        dispatch(b.build(), after);
    }

    private void dispatchSwipe(float sx, float sy, float ex, float ey, long duration, Runnable after) {
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0L, duration));
        dispatch(b.build(), after);
    }

    private void dispatchLongPress(float x, float y, long duration, Runnable after) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0L, duration));
        dispatch(b.build(), after);
    }

    private void dispatch(GestureDescription gesture, Runnable after) {
        final boolean[] finished = {false};
        Runnable done = () -> {
            if (finished[0]) return;
            finished[0] = true;
            suppressDetectionUntil = SystemClock.elapsedRealtime() + 130L;
            if (after != null) after.run();
        };
        boolean ok = false;
        try {
            ok = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { handler.post(done); }
                @Override public void onCancelled(GestureDescription g) { handler.post(done); }
            }, null);
        } catch (Throwable ignored) {}
        if (!ok) handler.postDelayed(done, 35L);
    }

    private void showMenu(AccessibilityNodeInfo editable, String sig) {
        if (wm == null || editable == null) return;
        hideMenusOnly();
        captureClipboard();

        try { target = AccessibilityNodeInfo.obtain(editable); }
        catch (Throwable t) { target = editable; }
        targetSignature = sig;
        editable.getBoundsInScreen(targetBounds);
        if (targetBounds.isEmpty()) return;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(35, 35, 35));
        bg.setCornerRadius(dp(6));
        bar.setBackground(bg);
        bar.setElevation(dp(4));

        TextView paste = menuItem("DÁN", 9.2f);
        paste.setOnClickListener(v -> pasteLatest());
        bar.addView(paste, new LinearLayout.LayoutParams(dp(33), dp(21)));

        TextView hist = menuItem("▤", 11f);
        hist.setContentDescription("Lịch sử sao chép");
        hist.setOnClickListener(v -> showHistory());
        bar.addView(hist, new LinearLayout.LayoutParams(dp(19), dp(21)));

        int[] pos = position(targetBounds, dp(54), dp(23));
        menu = bar;
        try {
            wm.addView(bar, overlayParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    pos[0], pos[1]));
            handler.removeCallbacks(menuTimeout);
            handler.postDelayed(menuTimeout, MENU_TIMEOUT_MS);
            handler.postDelayed(this::armFirstIfFocused, 260L);
        } catch (Throwable t) {
            menu = null;
            handler.postDelayed(this::armFirstIfFocused, 80L);
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
        handler.removeCallbacks(menuTimeout);
        boolean ok = pasteToTarget();
        if (ok) incrementCount();
        else Toast.makeText(this, "Không dán được vào ô này.", Toast.LENGTH_SHORT).show();
        hideMenusOnly();
        resetNativeSignal();
        handler.postDelayed(this::armFirstIfFocused, 120L);
    }

    private void showHistory() {
        captureClipboard();
        List<ClipboardHistoryStore.Item> items = ClipboardHistoryStore.load(this);
        if (items.isEmpty()) {
            Toast.makeText(this, "Chưa có lịch sử sao chép.", Toast.LENGTH_SHORT).show();
            return;
        }
        hideHistoryOnly();
        removeCatcher();

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
        title.setOnClickListener(v -> {
            hideHistoryOnly();
            handler.postDelayed(this::armFirstIfFocused, 100L);
        });
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
            handler.removeCallbacks(menuTimeout);
            handler.postDelayed(menuTimeout, 12000L);
        } catch (Throwable t) {
            historyPanel = null;
            handler.postDelayed(this::armFirstIfFocused, 100L);
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
            hideMenusOnly();
            handler.postDelayed(this::armFirstIfFocused, 100L);
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
        } catch (Throwable ignored) { return false; }
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

    private void rememberSelectedText(AccessibilityNodeInfo n) {
        if (n == null || n.getText() == null) return;
        try {
            int s = n.getTextSelectionStart();
            int e = n.getTextSelectionEnd();
            String t = n.getText().toString();
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

    private void removeCatcher() {
        handler.removeCallbacks(catcherTimeout);
        if (catcher != null && wm != null) {
            try { wm.removeView(catcher); } catch (Throwable ignored) {}
        }
        catcher = null;
        catcherMode = MODE_NONE;
        catcherSignature = "";
        catcherPackage = "";
        catcherTarget = null;
        catcherBounds.setEmpty();
    }

    private void hideMenusOnly() {
        handler.removeCallbacks(menuTimeout);
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

    private void resetNativeSignal() {
        lastNativeSignalAt = 0L;
        lastNativeSignalSignature = "";
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
        removeCatcher();
        hideMenusOnly();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeCatcher();
        hideMenusOnly();
        if (clipboard != null) {
            try { clipboard.removePrimaryClipChangedListener(clipListener); } catch (Throwable ignored) {}
        }
        ShizukuClipboardBridge.stop();
        super.onDestroy();
    }
}
