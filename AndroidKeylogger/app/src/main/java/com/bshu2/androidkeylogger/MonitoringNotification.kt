package com.bshu2.androidkeylogger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/** Ongoing, always-visible indicator that capture is running. Not dismissible. */
object MonitoringNotification {
    private const val CHANNEL_ID = "monitoring"
    const val NOTIFICATION_ID = 1001

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!Prefs.hasConsent(ctx)) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(ctx, nm)
        val enabled = Prefs.captureEnabled(ctx)
        val title = if (enabled) "監視中" else "監視 一時停止中"
        val text = if (enabled) {
            "同意のもとでキーストロークを記録しています。タップして管理。"
        } else {
            "キーストロークの記録は一時停止中です。"
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), flags()
        )
        val toggleAction = if (enabled) ControlReceiver.ACTION_PAUSE else ControlReceiver.ACTION_RESUME
        val toggleLabel = if (enabled) "一時停止" else "再開"
        val toggle = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, ControlReceiver::class.java).setAction(toggleAction),
            flags()
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, toggleLabel, toggle)
        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e(UploadPipeline.TAG, "could not post monitoring notification", e)
        }
    }

    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(ctx: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            val channel = NotificationChannel(
                CHANNEL_ID, "監視", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "記録が有効な間の常時表示インジケーター"
            nm.createNotificationChannel(channel)
        }
    }

    private fun flags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f = f or PendingIntent.FLAG_IMMUTABLE
        return f
    }
}
