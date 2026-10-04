package com.mrtien.autoskip;

import android.content.ClipData;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/** Optional Android 10+ global clipboard reader. Requires the separate Shizuku app. */
public final class ShizukuClipboardBridge {
    public static final int REQUEST_CODE = 7722;
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final long POLL_MS = 1100L;

    private static Context appContext;
    private static HandlerThread thread;
    private static Handler handler;
    private static volatile boolean running;
    private static Object clipboardService;
    private static String lastSignature = "";

    private ShizukuClipboardBridge() {}

    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized boolean start(Context context) {
        if (running) return true;
        if (context == null || !hasPermission()) return false;
        appContext = context.getApplicationContext();

        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/", "Landroid/os/");
        } catch (Throwable ignored) {
        }

        try {
            clipboardService = buildClipboardService();
            if (clipboardService == null) return false;
        } catch (Throwable ignored) {
            clipboardService = null;
            return false;
        }

        thread = new HandlerThread("AdBlockPaste-Clipboard");
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                captureOnce();
                Handler h = handler;
                if (h != null) h.postDelayed(this, POLL_MS);
            }
        });
        return true;
    }

    public static synchronized void stop() {
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        clipboardService = null;
        lastSignature = "";
    }

    public static boolean isRunning() {
        return running;
    }

    public static ClipData readNow() {
        try {
            if (!hasPermission()) return null;
            Object service = clipboardService;
            if (service == null) service = buildClipboardService();
            if (service == null) return null;
            Method method = findMethod(service, "getPrimaryClip");
            if (method == null) return null;
            Object result = method.invoke(service, buildArgs(method));
            return result instanceof ClipData ? (ClipData) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void captureOnce() {
        ClipData clip = readNow();
        if (clip == null || clip.getItemCount() == 0 || appContext == null) return;
        String sig = signature(clip);
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;
        ClipboardHistoryStore.addClip(appContext, clip);
    }

    private static Object buildClipboardService() throws Exception {
        android.os.IBinder binder = SystemServiceHelper.getSystemService("clipboard");
        if (binder == null) return null;
        android.os.IBinder wrapped = new ShizukuBinderWrapper(binder);
        Class<?> stub = Class.forName("android.content.IClipboard$Stub");
        Method asInterface = stub.getMethod("asInterface", android.os.IBinder.class);
        return asInterface.invoke(null, wrapped);
    }

    private static Method findMethod(Object target, String name) {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (name.equals(method.getName())) return method;
        }
        return null;
    }

    private static Object[] buildArgs(Method method) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean firstString = true;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == String.class) {
                args[i] = firstString ? SHELL_PACKAGE : null;
                firstString = false;
            } else if (t == int.class || t == Integer.TYPE) {
                args[i] = 0;
            } else if (t == boolean.class || t == Boolean.TYPE) {
                args[i] = false;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static String signature(ClipData clip) {
        try {
            if (clip == null || clip.getItemCount() == 0) return "";
            ClipData.Item item = clip.getItemAt(0);
            if (item.getText() != null) return "T|" + item.getText();
            if (item.getUri() != null) return "U|" + item.getUri();
            if (item.getIntent() != null) return "I|" + item.getIntent().toUri(0);
        } catch (Throwable ignored) {
        }
        return String.valueOf(System.currentTimeMillis());
    }
}
