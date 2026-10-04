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
 * v2.2.5 - persistent focused-field touch proxy.
 *
 * Previous builds needed Honor/MagicOS to emit a fresh Accessibility click event for
 * every new double tap. MagicOS often does not do that once an editor is already
 * focused. This version keeps ONE transparent, touchable Accessibility overlay only
 * over the currently focused editable field. It therefore sees every later tap
 * directly, without covering the keyboard or the rest of the screen.
 *
 * Every intercepted single tap / double tap / long press / swipe is immediately
 * re-injected to the real app. Only a confirmed physical double tap additionally
 * opens the tiny DÁN + history pill.
 */
public class ReliablePasteAccessibilityService extends AccessibilityService {

    private static final long DOUBLE_MAX_MS = 500L;
    private static final long LONG_PRESS_MS = 470L;
    private static final long MENU_TIMEOUT_MS = 10000L;
    private static final long REINSTALL_DELAY_MS = 18L;
    private static final long SYNTHETIC_EVENT_GUARD_MS = 220L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private ClipboardManager clipboard;

    // Persistent proxy over the focused editor only.
    private View proxy;
    private String proxySignature = "";
    private String proxyPackage = "";
    private AccessibilityNodeInfo proxyTarget;
    private final Rect proxyBounds = new Rect();
    private boolean forwardingGesture;
    private long suppressSeedUntil;

    // Physical gesture state received by proxy.
    private float downX;
    private float downY;
    private long downAt;
    private boolean moved;
    private long lastTapAt;
    private float lastTapX;
    private float lastTapY;
    private String lastTapSignature = "";

    // Paste menu + target.
    private LinearLayout menu;
    private LinearLayout historyPanel;
    private AccessibilityNodeInfo target;
    private String targetSignature = "";
    private final Rect targetBounds = new Rect();

    private String lastSelectedText = "";
    private long lastSelectedAt;

    private final Runnable hideMenuRunnable = this::hideMenusOnly;
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
        handler.postDelayed(this::refreshFocusedEditor, 150L);
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
            removeProxy(false);
            hideMenusOnly();
            resetTapState();
            return;
        }

        // Ignore events created by our own overlays.
        if (getPackageName().equals(pkg)) return;

        // Typing must never remove the proxy. Only reset the double-tap clock so a
        // keyboard edit cannot accidentally become tap #1.
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            resetTapState();
            handler.postDelayed(this::refreshFocusedEditor, 16L);
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            AccessibilityNodeInfo editable = resolveEditable(event);
            if (editable != null && isEditableLoose(editable)) {
                String epkg = editable.getPackageName() == null ? pkg : editable.getPackageName().toString();
                if (!getPackageName().equals(epkg)) {
                    boolean changed = !signature(editable, epkg).equals(proxySignature);
                    ensureProxy(editable, epkg);

                    // The very first physical tap into an unfocused field happened
                    // before our proxy existed. Seed it so the user's second physical
                    // tap still completes a 2-tap gesture rather than requiring 3 taps.
                    long now = SystemClock.elapsedRealtime();
                    if (changed
                            && now >= suppressSeedUntil
                            && !forwardingGesture
                            && (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED)) {
                        lastTapAt = now;
                        Rect b = new Rect();
                        editable.getBoundsInScreen(b);
                        lastTapX = b.centerX();
                        lastTapY = b.centerY();
                        lastTapSignature = signature(editable, epkg);
                    }
                    return;
                }
            }

            // Window changed and there is no longer an editable focus: stop proxying.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                handler.postDelayed(this::refreshFocusedEditor, 45L);
            }
        }
    }

    private void refreshFocusedEditor() {
        if (!enabled() || forwardingGesture) return;
        AccessibilityNodeInfo n = findFocusedEditable();
        if (n == null || !isEditableLoose(n)) {
            removeProxy(false);
            resetTapState();
            return;
        }
        String pkg = n.getPackageName() == null ? "" : n.getPackageName().toString();
        if (getPackageName().equals(pkg)) {
            removeProxy(false);
            return;
        }
        ensureProxy(n, pkg);
    }

    private void ensureProxy(AccessibilityNodeInfo editable, String pkg) {
        if (wm == null || editable == null || forwardingGesture) return;
        if (!isEditableLoose(editable)) return;

        String sig = signature(editable, pkg);
        Rect b = new Rect();
        editable.getBoundsInScreen(b);
        if (b.isEmpty() || b.width() < dp(18) || b.height() < dp(18)) return;

        if (proxy != null && sig.equals(proxySignature) && b.equals(proxyBounds)) return;

        removeProxy(true);
        proxySignature = sig;
        proxyPackage = pkg == null ? "" : pkg;
        proxyBounds.set(b);
        try { proxyTarget = AccessibilityNodeInfo.obtain(editable); }
        catch (Throwable t) { proxyTarget = editable; }

        View v = new View(this);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setClickable(true);
        v.setOnTouchListener(this::onProxyTouch);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                b.width(), b.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = b.left;
        lp.y = b.top;

        proxy = v;
        try {
            wm.addView(v, lp);
        } catch (Throwable t) {
            proxy = null;
        }
    }

    private boolean onProxyTouch(View v, MotionEvent event) {
        if (event == null) return true;
        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                downAt = SystemClock.elapsedRealtime();
                moved = false;
                // If an old paste menu is visible and the user starts a new gesture in
                // the editor, close the old menu but keep detecting immediately.
                hideMenusOnly();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (distanceSq(x, y, downX, downY) > dp(12) * dp(12)) moved = true;
                return true;

            case MotionEvent.ACTION_UP:
                long now = SystemClock.elapsedRealtime();
                long duration = now - downAt;
                String sig = proxySignature;
                String pkg = proxyPackage;
                AccessibilityNodeInfo saved = proxyTarget;

                if (moved) {
                    resetTapState();
                    forwardSwipe(downX, downY, x, y,
                            Math.max(120L, Math.min(duration, 650L)), sig, pkg, saved);
                    return true;
                }

                if (duration >= LONG_PRESS_MS) {
                    resetTapState();
                    forwardLongPress(x, y,
                            Math.max(520L, Math.min(duration, 900L)), sig, pkg, saved);
                    return true;
                }

                boolean sameField = sig.equals(lastTapSignature);
                boolean closeEnough = distanceSq(x, y, lastTapX, lastTapY) <= dp(58) * dp(58);
                long dt = now - lastTapAt;
                boolean isDouble = lastTapAt > 0L && sameField && closeEnough
                        && dt > 45L && dt <= DOUBLE_MAX_MS;

                if (isDouble) {
                    resetTapState();
                    forwardTap(x, y, sig, pkg, saved, true);
                } else {
                    lastTapAt = now;
                    lastTapX = x;
                    lastTapY = y;
                    lastTapSignature = sig;
                    forwardTap(x, y, sig, pkg, saved, false);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                resetTapState();
                handler.postDelayed(this::refreshFocusedEditor, 25L);
                return true;
        }
        return true;
    }

    private void forwardTap(float x, float y, String sig, String pkg,
                            AccessibilityNodeInfo saved, boolean showPaste) {
        Path p = new Path();
        p.moveTo(x, y);
        forwardGesture(new GestureDescription.StrokeDescription(p, 0L, 38L),
                sig, pkg, saved, () -> {
                    if (showPaste) {
                        AccessibilityNodeInfo e = findBySignature(sig);
                        if (e == null) e = findFocusedEditable();
                        if (e == null) e = saved;
                        if (isEditableLoose(e)) showMenu(e, sig);
                    }
                });
    }

    private void forwardSwipe(float sx, float sy, float ex, float ey, long duration,
                              String sig, String pkg, AccessibilityNodeInfo saved) {
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        forwardGesture(new GestureDescription.StrokeDescription(p, 0L, duration),
                sig, pkg, saved, null);
    }

    private void forwardLongPress(float x, float y, long duration,
                                  String sig, String pkg, AccessibilityNodeInfo saved) {
        Path p = new Path();
        p.moveTo(x, y);
        forwardGesture(new GestureDescription.StrokeDescription(p, 0L, duration),
                sig, pkg, saved, null);
    }

    private void forwardGesture(GestureDescription.StrokeDescription stroke,
                                String sig, String pkg, AccessibilityNodeInfo saved,
                                Runnable after) {
        forwardingGesture = true;
        suppressSeedUntil = SystemClock.elapsedRealtime() + SYNTHETIC_EVENT_GUARD_MS;
        removeProxy(true);

        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(stroke);

        Runnable done = () -> {
            forwardingGesture = false;
            AccessibilityNodeInfo e = findBySignature(sig);
            if (e == null) e = findFocusedEditable();
            if (e == null) e = saved;
            if (isEditableLoose(e)) {
                String epkg = e.getPackageName() == null ? pkg : e.getPackageName().toString();
                ensureProxy(e, epkg);
            } else {
                handler.postDelayed(this::refreshFocusedEditor, REINSTALL_DELAY_MS);
            }
            if (after != null) after.run();
        };

        boolean ok = false;
        try {
            ok = dispatchGesture(b.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    handler.postDelayed(done, REINSTALL_DELAY_MS);
                }
                @Override public void onCancelled(GestureDescription gestureDescription) {
                    handler.postDelayed(done, REINSTALL_DELAY_MS);
                }
            }, null);
        } catch (Throwable ignored) {}
        if (!ok) handler.postDelayed(done, 30L);
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
        bar.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(35, 35, 35));
        bg.setCornerRadius(dp(5));
        bar.setBackground(bg);
        bar.setElevation(dp(4));

        TextView paste = menuItem("DÁN", 8.5f);
        paste.setOnClickListener(v -> pasteLatest());
        bar.addView(paste, new LinearLayout.LayoutParams(dp(30), dp(20)));

        TextView hist = menuItem("▤", 10.5f);
        hist.setContentDescription("Lịch sử sao chép");
        hist.setOnClickListener(v -> showHistory());
        bar.addView(hist, new LinearLayout.LayoutParams(dp(18), dp(20)));

        int[] pos = position(targetBounds, dp(50), dp(22));
        menu = bar;
        try {
            wm.addView(bar, overlayParams(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, pos[0], pos[1]));
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
        hideMenusOnly();
        resetTapState();
        handler.postDelayed(this::refreshFocusedEditor, 30L);
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
        bg.setCornerRadius(dp(11));
        root.setBackground(bg);
        root.setElevation(dp(8));

        TextView title = menuItem("LỊCH SỬ   ✕", 12f);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setOnClickListener(v -> {
            hideHistoryOnly();
            handler.postDelayed(this::refreshFocusedEditor, 25L);
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
            hideMenusOnly();
            resetTapState();
            handler.postDelayed(this::refreshFocusedEditor, 30L);
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
            if (isEditableLoose(n) && (n.isFocused() || n.isEditable())) return n;
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
                    if (isEditableLoose(n)) return n;
                }
            }
        } catch (Throwable ignored) {}
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo n = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (isEditableLoose(n)) return n;
        } catch (Throwable ignored) {}
        return null;
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
        return (pkg == null ? "" : pkg) + "|" + (id == null ? "" : id)
                + "|" + cls + "|" + r.flattenToString();
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
        while (!q.isEmpty() && visited++ < 1200) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n == null) continue;
            String p = n.getPackageName() == null ? proxyPackage : n.getPackageName().toString();
            if (isEditableLoose(n) && sig.equals(signature(n, p))) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.addLast(c);
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
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        return lp;
    }

    private void removeProxy(boolean keepIdentity) {
        if (proxy != null && wm != null) {
            try { wm.removeView(proxy); } catch (Throwable ignored) {}
        }
        proxy = null;
        proxyBounds.setEmpty();
        if (!keepIdentity) {
            proxySignature = "";
            proxyPackage = "";
            proxyTarget = null;
        }
    }

    private void hideMenusOnly() {
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

    private void resetTapState() {
        lastTapAt = 0L;
        lastTapX = 0f;
        lastTapY = 0f;
        lastTapSignature = "";
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
        removeProxy(false);
        hideMenusOnly();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeProxy(false);
        hideMenusOnly();
        if (clipboard != null) {
            try { clipboard.removePrimaryClipChangedListener(clipListener); } catch (Throwable ignored) {}
        }
        ShizukuClipboardBridge.stop();
        super.onDestroy();
    }
}
