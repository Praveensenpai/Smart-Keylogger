# ⌨️ Smart Keylogger (スマート・キーロガー) — Consent-Gated Android Monitoring

> **A local-first monitoring app that refuses to record without recorded consent. Password fields are excluded in code, not left to the OS, and every log is yours to pause, revoke, or delete.**

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Transport](https://img.shields.io/badge/Transport-Opt--in%20Telegram-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://core.telegram.org/bots/api)
[![Server](https://img.shields.io/badge/Server-None%20Required-a6e3a1?style=for-the-badge)](https://github.com/Praveensenpai/Smart-Keylogger)

> [!WARNING]
> **Authorized use only.** This tool is for devices you own, with the knowledge and consent of everyone who uses them. Installing it on someone else's device without their consent is illegal in most jurisdictions.

---

## 🧭 The Six Controls

Built around six non-negotiable controls. Each one is enforced in code and was verified on a live device.

| # | Control | Where it lives |
|---|---|---|
| 1 | **Disclosure + recorded consent** — nothing runs until the consent checkbox is ticked and saved | `MainActivity`, `Prefs.grantConsent` |
| 2 | **Persistent ongoing notification** with a Pause / Resume action | `MonitoringNotification`, `ControlReceiver` |
| 3 | **Explicit password exclusion** — field flags *and* inputType variants, fails closed | `KeyloggerService.isSensitiveField` |
| 4 | **Local-first, opt-in upload** — capture works with zero network; Telegram is off until you configure your own bot | `Prefs.uploadEnabled`, `UploadPipeline` |
| 5 | **Pause / stop / delete-all** — from the notification and from the in-app Settings tab | `ControlReceiver`, `feature/settings` |
| 6 | **Data minimization** — 2-day archive retention, 500-char event cap, 8 MB rotation chunks | `UploadPipeline` |

---

## 🖥️ v3.1 — Compose UI with Live Status and Log Viewer

v3.1 replaces the two-activity XML UI with a single-activity Jetpack Compose / Material 3 app: a three-tab bottom bar (Home / Logs / Settings). The capture and upload pipeline is byte-for-byte unchanged from v3.0.

- **Home** — consent disclosure, plus a live status monitor: accessibility-service binding (read live from `Settings.Secure`) vs. the app's own capture switch, so `On` + `Paused` states are distinguishable. The master upload switch stays disabled while the service is unbound.
- **Logs** — read-only viewer of the last 1000 lines, seeded from the newest archive and then following the live file on a 1-second tail. It never writes, rotates, or deletes; uploads still send the complete files on your interval.
- **Settings** — upload toggle, masked bot token (flagged as a password to accessibility too, so the guard skips it), chat ID, interval, blocklist, delete-all.

Consent version bumped to 2 for the new display surface: upgrading from v3.0 re-shows the disclosure and requires a fresh consent. `minSdk` is 24.

---

## 🔄 How It Works

```text
┌──────────────────────────────────────┐
│  📱 Android device                   │
│  AccessibilityService                │
│  ┌────────────────────────────────┐  │
│  │ consent gate  (Prefs)          │  │   no consent → zero writes
│  ├────────────────────────────────┤  │
│  │ paused gate   (notification)   │  │   paused      → zero writes
│  ├────────────────────────────────┤  │
│  │ password guard + app blocklist │  │   match       → zero writes
│  └────────────────┬───────────────┘  │
└───────────────────┼──────────────────┘
                    │ append line
                    ▼
┌──────────────────────────────────────┐
│  💾 App-private local storage        │
│  · pending.txt                       │  ← adb pull anytime
│  · keylog_YYYYMMDD.txt  (2-day keep) │
└───────────────────┬──────────────────┘
                    │ upload only if enabled + configured
                    │ AlarmManager RTC_WAKEUP, user interval
                    ▼
┌──────────────────────────────────────┐
│  ☁️ Your Telegram bot (optional)     │
│  · sendDocument (multipart)          │
│  · file deleted only on HTTP 2xx     │
└───────────────────┬──────────────────┘
                    ▼
┌──────────────────────────────────────┐
│  💬 Your chat                        │
└──────────────────────────────────────┘
```

Each recorded line carries timestamp, device, app, window, action, and the captured text:

```text
2026-11-10T14:22:01Z | Redmi Note 4 | Notes | Draft | TEXT | finaltextcheck
```

Password fields, and any package on your blocklist, never reach that line.

---

## ✨ Features

- **🛡️ Consent is the master gate** — `Prefs.captureEnabled()` and `Prefs.uploadEnabled()` both return false without a recorded consent version, so a stale `true` in storage cannot resurrect capture
- **🔒 Password exclusion in code** — `isPassword()`, `TYPE_TEXT_VARIATION_PASSWORD`, `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `NUMBER_VARIATION_PASSWORD`, plus a per-app blocklist preloaded with 1Password, Bitwarden, LastPass, Dashlane, PayPal
- **📴 Local-first** — every event hits disk before any network [!NOTE]
> On MIUI and some OEM builds the accessibility layer exposes plaintext from password fields to the OS. The in-code password guard is what blocks those fields from being logged — verified on a Redmi Note 4 running MIUI.

---

## ⚙️ Build From Source

```bash
git clone https://github.com/Praveensenpai/Smart-Keylogger
cd Smart-Keylogger/AndroidKeylogger
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17. Kotlin 2.0.20 / AGP 8.7.3 / Gradle 8.9 / compileSdk 35 / minSdk 24 / Jetpack Compose (BOM 2024.09.03).

No credentials are compiled in. Upload targets are set at runtime in Settings.

---

## 🧪 Verifying It Works

```bash
# Watch capture and upload decisions
adb logcat -s Keylogger

# Inspect local state
adb shell ls -l /sdcard/Android/data/com.bshu2.androidkeylogger/files/

# Pull the daily archive
adb pull /sdcard/Android/data/com.bshu2.androidkeylogger/files/keylog_$(date +%Y%m%d).txt
```

A successful upload logs the response before deleting the file:

```text
D Keylogger: sendDocument ok http=200 file=keylog_20261110_233401.txt bytes=725
D Keylogger: uploaded keylog_20261110_233401.txt
```

Proving the guards hold — the three checks that matter:

```bash
# 1. No consent → no prefs file at all
adb shell run-as com.bshu2.androidkeylogger ls shared_prefs

# 2. Password field typed, then searched across every app file
adb shell run-as com.bshu2.androidkeylogger grep -rl SuperSecretPW99 .

# 3. Paused from the notification, typed, then checked for any new line
adb shell ls -l /sdcard/Android/data/com.bshu2.androidkeylogger/files/
```

All three return empty on v3.1, verified against live hardware with the [device retest harness](tools/device-retest.sh).

---

## 🛠️ Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Status stays "not consented" | Consent checkbox unticked | Tick it and tap Enable monitoring |
| Nothing captured | Accessibility service disabled | Re-enable under Settings → Accessibility |
| Nothing captured, notification says paused | Pause action was tapped | Tap Resume on the notification |
| Upload never happens | Upload toggle off or destination empty | Settings → enable upload, enter token + chat ID |
| Upload fails | Bot never messaged, or wrong chat ID | Send `/start` to the bot, re-check `getUpdates` |
| Files pile up in `outbox/` | Device offline or Telegram blocked | They replay automatically; pull via `adb` meanwhile |

---

## 📜 License

MIT — see [LICENSE](LICENSE). Provided as-is, no warranty. Use only on devices you own, with the consent of everyone who uses them.

---

<p align="center">Original concept by <a href="https://github.com/bshu2/Android-Keylogger">bshu2</a> · Telegram transport by <a href="https://www.youtube.com/channel/UC2O1Hfg-dDCbUcau5QWGcgg">Linuxndroid</a></p>