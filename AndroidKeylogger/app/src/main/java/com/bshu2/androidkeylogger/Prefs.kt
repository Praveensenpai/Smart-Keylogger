package com.bshu2.androidkeylogger

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for user state. Consent is the master gate: with no
 * recorded consent, capture and upload both stay off regardless of other keys.
 */
object Prefs {
    private const val FILE = "keylogger"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val KEY_CONSENT_AT = "consent_at"
    private const val KEY_CAPTURE_ENABLED = "capture_enabled"
    private const val KEY_UPLOAD_ENABLED = "upload_enabled"
    private const val KEY_BOT_TOKEN = "bot_token"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_INTERVAL_MS = "upload_interval_ms"
    private const val KEY_BLOCKLIST = "blocklist"
    private const val KEY_LAST_RUN = "last_upload_at"

    // v2 adds the on-device log viewer, a new display surface for captured
    // text, so consent is collected again on upgrade.
    const val CURRENT_CONSENT_VERSION = 2
    const val DEFAULT_INTERVAL_MS = 3_600_000L

    private val DEFAULT_BLOCKLIST = listOf(
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.bitwarden",
        "com.dashlane",
        "com.paypal.android.p2pmobile"
    )

    @Volatile
    private var cachedLastRun = -1L

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasConsent(ctx: Context): Boolean =
        prefs(ctx).getInt(KEY_CONSENT_VERSION, 0) >= CURRENT_CONSENT_VERSION

    fun grantConsent(ctx: Context) {
        prefs(ctx).edit()
            .putInt(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION)
            .putLong(KEY_CONSENT_AT, System.currentTimeMillis())
            .putBoolean(KEY_CAPTURE_ENABLED, true)
            .apply()
    }

    fun revokeConsent(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        cachedLastRun = -1L
    }

    fun captureEnabled(ctx: Context): Boolean =
        hasConsent(ctx) && prefs(ctx).getBoolean(KEY_CAPTURE_ENABLED, true)

    fun setCaptureEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
    }

    fun uploadEnabled(ctx: Context): Boolean =
        hasConsent(ctx) && prefs(ctx).getBoolean(KEY_UPLOAD_ENABLED, false)

    fun setUploadEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UPLOAD_ENABLED, enabled).apply()
    }

    fun intervalMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS).coerceAtLeast(60_000L)

    fun setIntervalMs(ctx: Context, value: Long) {
        prefs(ctx).edit().putLong(KEY_INTERVAL_MS, value.coerceAtLeast(60_000L)).apply()
    }

    fun blocklist(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_BLOCKLIST, null)
        val items = if (raw == null) DEFAULT_BLOCKLIST else parseList(raw)
        return items.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setBlocklist(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_BLOCKLIST, value).apply()
    }

    fun blocklistText(ctx: Context): String =
        prefs(ctx).getString(KEY_BLOCKLIST, DEFAULT_BLOCKLIST.joinToString("\n")) ?: ""

    private fun parseList(raw: String): List<String> =
        raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun lastRunAt(ctx: Context): Long {
        val cached = cachedLastRun
        if (cached >= 0) return cached
        val stored = prefs(ctx).getLong(KEY_LAST_RUN, 0L)
        cachedLastRun = stored
        return stored
    }

    fun markRun(ctx: Context) {
        val now = System.currentTimeMillis()
        cachedLastRun = now
        prefs(ctx).edit().putLong(KEY_LAST_RUN, now).apply()
    }

    fun resetLastRun(ctx: Context) {
        cachedLastRun = 0L
        prefs(ctx).edit().remove(KEY_LAST_RUN).apply()
    }

    fun botToken(ctx: Context): String =
        (prefs(ctx).getString(KEY_BOT_TOKEN, "") ?: "").trim()

    fun chatId(ctx: Context): String =
        (prefs(ctx).getString(KEY_CHAT_ID, "") ?: "").trim()

    fun setDestination(ctx: Context, token: String, chat: String) {
        prefs(ctx).edit()
            .putString(KEY_BOT_TOKEN, token.trim())
            .putString(KEY_CHAT_ID, chat.trim())
            .apply()
    }
}
