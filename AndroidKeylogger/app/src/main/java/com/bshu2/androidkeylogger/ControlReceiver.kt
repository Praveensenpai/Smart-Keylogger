package com.bshu2.androidkeylogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles Pause/Resume taps on the ongoing monitoring notification. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_PAUSE -> Prefs.setCaptureEnabled(context, false)
            ACTION_RESUME -> Prefs.setCaptureEnabled(context, true)
        }
        MonitoringNotification.update(context)
    }

    companion object {
        const val ACTION_PAUSE = "com.bshu2.androidkeylogger.action.PAUSE"
        const val ACTION_RESUME = "com.bshu2.androidkeylogger.action.RESUME"
    }
}
