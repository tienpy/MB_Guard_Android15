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
 * v2.2: universal paste shortcut.
 *
 * No Touch Exploration is used. Instead a transparent accessibility overlay is placed
 * ONLY over the currently focused editable field. The keyboard and the rest of the
 * screen never get covered. Normal single taps are re-injected to the edit field;
 * the second tap of a double-tap opens an iOS-like DÁN + history menu.
 */
public class AdPasteAccessibilityService extends AccessibilityService {

    private static final long DOUBLE_TAP_MIN_MS = 70L;
    private static final long DOUBLE_TAP_MAX_MS = 480L;
    private static final long MENU_TIMEOUT_MS = 5200L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private ClipboardManager clipboardManager;

    private View touchCatcher;
    private WindowManager.LayoutParams catcherParams;
    private boolean catcherTemporarilyUntouchable;
    private String catcherSignature = "";
    private final Rect catcherBounds = new Rect();

    private float downX;
    private float downY;
    private long downAt;
    private boolean moved;

    private long lastTapAt;
    private float lastTapX;
    private float lastTapY;
    private String lastTapSignature = "";

    private LinearLayout pasteMenu;
    private View historyPanel;
    private AccessibilityNodeInfo menuTarget;
    private String menuTargetSignature = "";
    private final Rect menuTargetBounds = new Rect();

    private String lastForegroundPackage = "";
    private String lastSelectedText = "";
    private long lastSelectedAt;

    private final Runnable hideMenusRunnable = () -> {
        hidePasteMenu();
        hideHistoryPanel();
        refreshFocusedFieldSoon();
    };

    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener =
            () -> handler.postDelayed(this::captureCurrentClipboardBestEffort, 90L);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            try {
                clipboardManager.addPrimaryClipChangedListener(clipboardListener);
            } catch (Throwable ignored) {
            }
        }
        if (ShizukuClipboardBridge.hasPermission()) {
            ShizukuClipboardBridge.start(this);
        }
        refreshFocusedFieldSoon();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!pkg.isEmpty()) lastForegroundPackage = pkg;

        // Capture selected text as a fallback history source.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            rememberSelectedText(event.getSource());
        }

        // Contextual Copy / Sao chép buttons are often exposed as accessibility clicks.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String label = nodeLabel(event.getSource());
            if (isCopyLabel(label)) {
                if (!lastSelectedText.isEmpty() && SystemClock.elapsedRealtime() - lastSelectedAt < 10000L) {
                    ClipboardHistoryStore.addText(this, lastSelectedText);
                }
                handler.postDelayed(this::captureCurrentClipboardBestEffort, 120L);
            }
        }

        if (!isDoubleTapPasteEnabled()) {
            removeTouchCatcher();
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            refreshFocusedFieldSoon();
        }
    }

    private void refreshFocusedFieldSoon() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 85L);
    }

    private final Runnable refreshRunnable = () -> {
        if (pasteMenu != null || historyPanel != null) return;
        AccessibilityNodeInfo editable = findFocusedEditableInWindows();
        if (!isEditableNode(editable)) {
            removeTouchCatcher();
            return;
        }
        String pkg = editable.getPackageName() == null
                ? lastForegroundPackage : editable.getPackageName().toString();
        if (getPackageName().equals(pkg)) {
            removeTouchCatcher();
            return;
        }
        installTouchCatcher(editable, nodeSignature(editable, pkg));
    };

    private void installTouchCatcher(AccessibilityNodeInfo editable, String signature) {
        if (windowManager == null || editable == null || editable.isPassword()) return;
        Rect b = new Rect();
        editable.getBoundsInScreen(b);
        if (b.isEmpty() || b.width() < dp(20) || b.height() < dp(20)) {
            removeTouchCatcher();
            return;
        }

        if (touchCatcher != null && signature.equals(catcherSignature) && b.equals(catcherBounds)) {
            return;
        }

        removeTouchCatcher();
        catcherSignature = signature;
        catcherBounds.set(b);

        View v = new View(this);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setClickable(true);
        v.setOnTouchListener(this::onCatcherTouch);
        touchCatcher = v;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                b.width(),
                b.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = b.left;
        lp.y = b.top;
        catcherParams = lp;

        try {
            windowManager.addView(v, lp);
        } catch (Throwable t) {
            touchCatcher = null;
            catcherParams = null;
        }
    }

    private boolean onCatcherTouch(View view, MotionEvent event) {
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
                if (distanceSq(x, y, downX, downY) > dp(14) * dp(14)) moved = true;
                return true;

            case MotionEvent.ACTION_UP:
                long now = SystemClock.elapsedRealtime();
                long duration = now - downAt;

                if (moved) {
                    resetTapSequence();
                    forwardSwipe(downX, downY, x, y, Math.max(120L, Math.min(duration, 700L)));
                    return true;
                }

                if (duration >= 480L) {
                    resetTapSequence();
                    forwardLongPress(x, y, Math.min(duration, 900L));
                    return true;
                }

                boolean secondTap = catcherSignature.equals(lastTapSignature)
                        && now - lastTapAt >= DOUBLE_TAP_MIN_MS
                        && now - lastTapAt <= DOUBLE_TAP_MAX_MS
                        && distanceSq(x, y, lastTapX, lastTapY) <= dp(52) * dp(52);

                if (secondTap) {
                    resetTapSequence();
                    AccessibilityNodeInfo target = findFocusedEditableInWindows();
                    if (target != null) {
                        showPasteMenu(target, nodeSignature(target,
                                target.getPackageName() == null ? lastForegroundPackage
                                        : target.getPackageName().toString()));
                    }
                } else {
                    lastTapAt = now;
                    lastTapX = x;
                    lastTapY = y;
                    lastTapSignature = catcherSignature;
                    forwardTap(x, y);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                resetTapSequence();
                return true;
        }
        return true;
    }

    private void forwardTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 55L));
        dispatchToUnderlying(builder.build());
    }

    private void forwardLongPress(float x, float y, long duration) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(520L, duration)));
        dispatchToUnderlying(builder.build());
    }

    private void forwardSwipe(float sx, float sy, float ex, float ey, long duration) {
        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        dispatchToUnderlying(builder.build());
    }

    private void dispatchToUnderlying(GestureDescription gesture) {
        setCatcherTouchable(false);
        boolean dispatched;
        try {
            dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    handler.postDelayed(() -> setCatcherTouchable(true), 35L);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    handler.postDelayed(() -> setCatcherTouchable(true), 35L);
                }
            }, null);
        } catch (Throwable t) {
            dispatched = false;
        }
        if (!dispatched) handler.postDelayed(() -> setCatcherTouchable(true), 80L);
    }

    private void setCatcherTouchable(boolean touchable) {
        if (touchCatcher == null || catcherParams == null || windowManager == null) return;
        try {
            if (touchable) {
                catcherParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                catcherTemporarilyUntouchable = false;
            } else {
                catcherParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                catcherTemporarilyUntouchable = true;
            }
            windowManager.updateViewLayout(touchCatcher, catcherParams);
        } catch (Throwable ignored) {
        }
    }

    private void showPasteMenu(AccessibilityNodeInfo target, String signature) {
        if (windowManager == null || target == null) return;
        hidePasteMenu();
        hideHistoryPanel();
        removeTouchCatcher();
        captureCurrentClipboardBestEffort();

        try {
            menuTarget = AccessibilityNodeInfo.obtain(target);
        } catch (Throwable t) {
            menuTarget = target;
        }
        menuTargetSignature = signature;
        target.getBoundsInScreen(menuTargetBounds);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(3), dp(4), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(38, 38, 38));
        bg.setCornerRadius(dp(14));
        bar.setBackground(bg);
        bar.setElevation(dp(10));

        TextView paste = menuButton("DÁN");
        paste.setOnClickListener(v -> pasteLatest());
        bar.addView(paste, new LinearLayout.LayoutParams(dp(76), dp(44)));

        TextView history = menuButton("▤");
        history.setTextSize(22f);
        history.setContentDescription("Lịch sử sao chép");
        history.setOnClickListener(v -> showHistoryPanel());
        bar.addView(history, new LinearLayout.LayoutParams(dp(48), dp(44)));

        pasteMenu = bar;
        int[] pos = menuPosition(menuTargetBounds, dp(132), dp(50));
        WindowManager.LayoutParams lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, pos[0], pos[1]);
        try {
            windowManager.addView(bar, lp);
            handler.removeCallbacks(hideMenusRunnable);
            handler.postDelayed(hideMenusRunnable, MENU_TIMEOUT_MS);
        } catch (Throwable t) {
            pasteMenu = null;
            refreshFocusedFieldSoon();
        }
    }

    private TextView menuButton(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        return t;
    }

    private void pasteLatest() {
        boolean ok = performPasteOnTarget();
        if (ok) incrementPasteCount();
        else Toast.makeText(this, "Ô này không cho phép Dán tự động.", Toast.LENGTH_SHORT).show();
        hidePasteMenu();
        hideHistoryPanel();
        refreshFocusedFieldSoon();
    }

    private void showHistoryPanel() {
        captureCurrentClipboardBestEffort();
        hideHistoryPanel();
        List<ClipboardHistoryStore.Item> items = ClipboardHistoryStore.load(this);
        if (items.isEmpty()) {
            Toast.makeText(this, "Chưa có lịch sử sao chép.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(42, 42, 42));
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);
        root.setElevation(dp(12));

        TextView title = menuButton("LỊCH SỬ SAO CHÉP   ✕");
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setOnClickListener(v -> {
            hideHistoryPanel();
            handler.removeCallbacks(hideMenusRunnable);
            handler.postDelayed(hideMenusRunnable, MENU_TIMEOUT_MS);
        });
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        int count = Math.min(items.size(), 30);
        for (int i = 0; i < count; i++) {
            ClipboardHistoryStore.Item item = items.get(i);
            TextView row = new TextView(this);
            row.setText(item.preview());
            row.setTextColor(Color.WHITE);
            row.setTextSize(14f);
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(i % 2 == 0 ? Color.rgb(56, 56, 56) : Color.rgb(49, 49, 49));
            rowBg.setCornerRadius(dp(8));
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(4);
            list.addView(row, rp);
            row.setOnClickListener(v -> pasteHistoryItem(item));
        }

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260)));
        historyPanel = root;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(360), screenW - dp(20));
        int height = dp(318);
        int x = Math.max(dp(10), Math.min(menuTargetBounds.left, screenW - width - dp(10)));
        int y = menuTargetBounds.top - height - dp(8);
        if (y < dp(10)) y = Math.min(menuTargetBounds.bottom + dp(8), screenH - height - dp(10));

        WindowManager.LayoutParams lp = overlayParams(width, height, x, y);
        try {
            windowManager.addView(root, lp);
            handler.removeCallbacks(hideMenusRunnable);
            handler.postDelayed(hideMenusRunnable, 12000L);
        } catch (Throwable t) {
            historyPanel = null;
        }
    }

    private void pasteHistoryItem(ClipboardHistoryStore.Item item) {
        ClipData clip = ClipboardHistoryStore.toClipData(this, item);
        if (clip == null || clipboardManager == null) {
            Toast.makeText(this, "Mục lịch sử này không còn dùng được.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            clipboardManager.setPrimaryClip(clip);
        } catch (Throwable t) {
            Toast.makeText(this, "Không thể đưa mục này vào clipboard.", Toast.LENGTH_SHORT).show();
            return;
        }
        hideHistoryPanel();
        handler.postDelayed(() -> {
            if (performPasteOnTarget()) incrementPasteCount();
            else Toast.makeText(this, "Ứng dụng này không nhận kiểu nội dung đã chọn.", Toast.LENGTH_SHORT).show();
            hidePasteMenu();
            refreshFocusedFieldSoon();
        }, 100L);
    }

    private boolean performPasteOnTarget() {
        AccessibilityNodeInfo target = findFocusedEditableInWindows();
        if (target == null || !isEditableNode(target)) target = menuTarget;
        if (target == null) target = findEditableBySignature(menuTargetSignature);
        if (target == null) return false;
        try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void captureCurrentClipboardBestEffort() {
        ClipData clip = null;
        if (ShizukuClipboardBridge.hasPermission()) {
            if (!ShizukuClipboardBridge.isRunning()) ShizukuClipboardBridge.start(this);
            clip = ShizukuClipboardBridge.readNow();
        }
        if (clip == null && clipboardManager != null) {
            try {
                clip = clipboardManager.getPrimaryClip();
            } catch (Throwable ignored) {
            }
        }
        if (clip != null) ClipboardHistoryStore.addClip(this, clip);
    }

    private void rememberSelectedText(AccessibilityNodeInfo node) {
        if (node == null || node.getText() == null) return;
        try {
            int start = node.getTextSelectionStart();
            int end = node.getTextSelectionEnd();
            String text = node.getText().toString();
            if (start >= 0 && end > start && end <= text.length()) {
                lastSelectedText = text.substring(start, end);
                lastSelectedAt = SystemClock.elapsedRealtime();
            }
        } catch (Throwable ignored) {
        }
    }

    private String nodeLabel(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence text = node.getText();
        if (text == null || text.length() == 0) text = node.getContentDescription();
        return text == null ? "" : text.toString().trim().toLowerCase(Locale.ROOT);
    }

    private boolean isCopyLabel(String label) {
        if (label == null) return false;
        return "copy".equals(label) || "sao chép".equals(label) || "sao chep".equals(label);
    }

    private AccessibilityNodeInfo findFocusedEditableInWindows() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (int i = windows.size() - 1; i >= 0; i--) {
                    AccessibilityWindowInfo w = windows.get(i);
                    if (w == null) continue;
                    AccessibilityNodeInfo root = w.getRoot();
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

    private boolean isEditableNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled() || node.isPassword()) return false;
        if (node.isEditable() && node.isFocused()) return true;
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String c = cls.toString().toLowerCase(Locale.ROOT);
            if ((c.contains("edittext") || c.contains("textfield") || c.contains("searchview"))
                    && node.isFocused()) return true;
        }
        try {
            if (node.isFocused()) {
                for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                    if (action.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT
                            || action.getId() == AccessibilityNodeInfo.ACTION_PASTE) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String nodeSignature(AccessibilityNodeInfo node, String fallbackPackage) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        String pkg = node.getPackageName() == null ? fallbackPackage : node.getPackageName().toString();
        String id = node.getViewIdResourceName();
        String cls = node.getClassName() == null ? "" : node.getClassName().toString();
        return pkg + "|" + (id == null ? "" : id) + "|" + cls + "|" + r.flattenToString();
    }

    private AccessibilityNodeInfo findEditableBySignature(String signature) {
        if (signature == null || signature.isEmpty()) return null;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    AccessibilityNodeInfo found = findEditableBySignature(w.getRoot(), signature);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {
        }
        return findEditableBySignature(getRootInActiveWindow(), signature);
    }

    private AccessibilityNodeInfo findEditableBySignature(AccessibilityNodeInfo root, String signature) {
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int visited = 0;
        while (!q.isEmpty() && visited < 1000) {
            AccessibilityNodeInfo n = q.removeFirst();
            visited++;
            if (n == null) continue;
            String pkg = n.getPackageName() == null ? lastForegroundPackage : n.getPackageName().toString();
            if (isEditableNode(n) && signature.equals(nodeSignature(n, pkg))) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.addLast(child);
            }
        }
        return null;
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, int x, int y) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                height,
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

    private int[] menuPosition(Rect bounds, int width, int height) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int x = Math.max(dp(6), Math.min(bounds.right - width, screenW - width - dp(6)));
        int y = bounds.top - height - dp(6);
        if (y < dp(6)) y = Math.min(bounds.bottom + dp(6), screenH - height - dp(6));
        return new int[]{x, y};
    }

    private void hidePasteMenu() {
        handler.removeCallbacks(hideMenusRunnable);
        if (pasteMenu != null && windowManager != null) {
            try { windowManager.removeView(pasteMenu); } catch (Throwable ignored) {}
        }
        pasteMenu = null;
    }

    private void hideHistoryPanel() {
        if (historyPanel != null && windowManager != null) {
            try { windowManager.removeView(historyPanel); } catch (Throwable ignored) {}
        }
        historyPanel = null;
    }

    private void removeTouchCatcher() {
        if (touchCatcher != null && windowManager != null) {
            try { windowManager.removeView(touchCatcher); } catch (Throwable ignored) {}
        }
        touchCatcher = null;
        catcherParams = null;
        catcherSignature = "";
        catcherBounds.setEmpty();
        catcherTemporarilyUntouchable = false;
        resetTapSequence();
    }

    private void resetTapSequence() {
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

    private void incrementPasteCount() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        long count = p.getLong(MainActivity.KEY_PASTE_COUNT, 0L);
        p.edit().putLong(MainActivity.KEY_PASTE_COUNT, count + 1L).apply();
    }

    private boolean isDoubleTapPasteEnabled() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_DOUBLE_TAP_PASTE, true);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onInterrupt() {
        removeTouchCatcher();
        hidePasteMenu();
        hideHistoryPanel();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeTouchCatcher();
        hidePasteMenu();
        hideHistoryPanel();
        if (clipboardManager != null) {
            try { clipboardManager.removePrimaryClipChangedListener(clipboardListener); } catch (Throwable ignored) {}
        }
        ShizukuClipboardBridge.stop();
        super.onDestroy();
    }
}
