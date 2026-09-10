package com.mrtien.tiencallrecorder;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional helper for Android audio-input sharing during calls.
 * It does not inspect window content and performs no screen automation.
 */
public class RecorderAccessibilityService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
