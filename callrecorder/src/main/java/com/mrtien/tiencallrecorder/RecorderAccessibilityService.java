package com.mrtien.tiencallrecorder;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Honor Magic V3 helper: when a SIM call becomes OFFHOOK, find the phone app's
 * built-in Record/Ghi am control and press it once. The actual recording is
 * then performed by Honor's system dialer, so it can keep both call directions
 * even when a wired/Bluetooth headset is used (subject to the phone ROM's own
 * call-recording behavior).
 */
public class RecorderAccessibilityService extends AccessibilityService {
    private static final long RETRY_MS = 450L;
    private static final long MAX_SEARCH_MS = 20000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TelephonyManager telephonyManager;
    private PhoneCallback phoneCallback;

    private boolean callActive;
    private boolean recordPressedThisCall;
    private long callConnectedAt;

    private final Runnable retryRecord = new Runnable() {
        @Override
        public void run() {
            if (!callActive || recordPressedThisCall || !autoHonorEnabled()) {
                return;
            }
            if (System.currentTimeMillis() - callConnectedAt > MAX_SEARCH_MS) {
                setHonorStatus("Không tìm thấy nút Ghi âm HONOR trong 20 giây đầu cuộc gọi");
                return;
            }
            tryPressHonorRecord();
            if (!recordPressedThisCall) {
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
            callConnectedAt = System.currentTimeMillis();
            setHonorStatus("Đã kết nối cuộc gọi - đang tìm nút Ghi âm HONOR");
            handler.removeCallbacks(retryRecord);
            handler.postDelayed(retryRecord, 250L);
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            callActive = false;
            recordPressedThisCall = false;
            handler.removeCallbacks(retryRecord);
            setHonorStatus("Cuộc gọi đã kết thúc");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!callActive || recordPressedThisCall || !autoHonorEnabled()) {
            return;
        }
        int type = event == null ? 0 : event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            tryPressHonorRecord();
        }
    }

    private void tryPressHonorRecord() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        try {
            AccessibilityNodeInfo alreadyRecording = findNode(root, true);
            if (alreadyRecording != null) {
                recordPressedThisCall = true;
                setHonorStatus("HONOR đang ghi âm cuộc gọi");
                return;
            }

            AccessibilityNodeInfo recordNode = findNode(root, false);
            if (recordNode == null) {
                return;
            }

            AccessibilityNodeInfo clickable = recordNode;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }

            boolean clicked = clickable != null
                    && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);

            if (clicked) {
                recordPressedThisCall = true;
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                        .putInt("honor_auto_click_count",
                                getSharedPreferences("prefs", MODE_PRIVATE)
                                        .getInt("honor_auto_click_count", 0) + 1)
                        .apply();
                setHonorStatus("ĐÃ BẤM nút Ghi âm HONOR tự động");
                Toast.makeText(this, "Đã bật ghi âm cuộc gọi HONOR", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            setHonorStatus("Lỗi khi bấm Ghi âm HONOR: " + t.getClass().getSimpleName());
        } finally {
            root.recycle();
        }
    }

    /**
     * When looking for a start button, match Record/Ghi am but reject Stop/End
     * recording labels. When looking for active recording, match labels such as
     * Stop recording/Dung ghi am/Recording/Đang ghi.
     */
    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, boolean activeRecording) {
        if (node == null) {
            return null;
        }

        String label = normalize(labelOf(node));
        if (!label.isEmpty()) {
            if (activeRecording) {
                if (label.contains("dang ghi")
                        || label.contains("recording")
                        || label.contains("dung ghi am")
                        || label.contains("stop recording")
                        || label.contains("ket thuc ghi am")) {
                    return node;
                }
            } else {
                boolean recordWord = label.equals("ghi am")
                        || label.contains("ghi am cuoc goi")
                        || label.equals("record")
                        || label.contains("record call")
                        || label.contains("call record");
                boolean stopWord = label.contains("dung ghi")
                        || label.contains("stop record")
                        || label.contains("dang ghi")
                        || label.contains("recording");
                if (recordWord && !stopWord) {
                    return node;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(node.getChild(i), activeRecording);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String labelOf(AccessibilityNodeInfo node) {
        StringBuilder value = new StringBuilder();
        if (node.getText() != null) {
            value.append(node.getText()).append(' ');
        }
        if (node.getContentDescription() != null) {
            value.append(node.getContentDescription()).append(' ');
        }
        if (node.getViewIdResourceName() != null) {
            value.append(node.getViewIdResourceName());
        }
        return value.toString();
    }

    private String normalize(String source) {
        if (source == null) {
            return "";
        }
        String n = Normalizer.normalize(source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .trim();
        return n;
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
