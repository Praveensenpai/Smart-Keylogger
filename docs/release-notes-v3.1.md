## ⌨️ Smart Keylogger v3.1 — Compose Redesign with Live Status and Logs

> The capture engine is untouched. What changes is what you can see: a live
> status monitor, a real-time log viewer, and a Material 3 interface that
> replaces both XML activities with a single three-tab Compose shell.

### 🎯 What this release is (and is not)
The delivery path is unchanged. `UploadPipeline` still rotates the pending
queue and ships the **full log files** to your Telegram chat on your configured
interval, driven by `AlarmScheduler`. The on-screen log viewer is a read-only
convenience window over those same files — it never alters, truncates or
re-uploads them.

### ✨ What's new
- **Material 3 shell** — one `ComponentActivity` hosting `Scaffold` with a
  bottom `NavigationBar`: Home, Logs, Settings. Tab state survives rotation via
  `rememberSaveable`. No `navigation-compose`, no serialization plugin.
- **Live status monitor** — four cards, refreshed every 2 s:
  - Accessibility service: read live from
    `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, not from an app flag.
  - Capture: recording or paused, derived from service AND consent AND switch.
  - Upload: on only when enabled **and** a destination is configured.
  - Last upload: real timestamp from `Prefs.lastRunAt`.
- **Master switch** — toggles only the app's capture flag, and is disabled
  while the OS service is off, so "on" never means two different things.
- **Live log viewer** — the last 1000 lines, monospace, bounded in memory.
  Seeds from the newest daily archive (falling back to `pending.txt`, then the
  newest outbox file), then tails incrementally by byte offset once per second
  while the tab is visible.
- **Settings as a third tab** — upload toggle, bot token (masked), chat ID,
  interval, blocklist, save, and delete-all behind a confirmation dialog.
- **Dynamic color** on Android 12+; fixed Material 3 tokens below that.

### 🔍 Log viewer details
- Ring buffer capped at 1000 lines; the oldest line is dropped first.
- Reads only complete lines — a partial trailing write is left unconsumed, so a
  concurrent append can never split a record.
- Detects day rollover, file truncation and delete-all, and resets its offset.
- Auto-scroll follows only while you are already at the bottom, so reading
  older lines is not interrupted.

### 🔐 Consent re-collected
`CURRENT_CONSENT_VERSION` is now **2**. The log viewer is a new display surface
for captured text, so upgrading users are asked to consent again rather than
silently gaining a new way to read their data.

### 🔧 Stack
- Jetpack Compose (BOM 2024.09.03), Material 3, `activity-compose` 1.9.2,
  `lifecycle-runtime-compose` 2.8.6, Kotlin Compose compiler plugin 2.0.20.
- `appcompat` and `constraintlayout` removed; `core-ktx` declared explicitly.
- minSdk raised 21 to **24** (the Redmi Note 4 on API 24 is the proven target).
- versionCode **5**, versionName **3.1**.
- XML activities `MainActivity`/`SettingsActivity` and their layouts deleted;
  `SettingsActivity` removed from the manifest.

### 🧪 Verification
- `./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL, 16,534,842-byte APK.
- `./gradlew :app:testDebugUnitTest` -> 9 tests, 0 failures: ring cap,
  blank-line skipping, offset resume without duplicates, partial-line safety,
  truncation reset, tail-N selection, missing-file handling.
- On-device smoke on the Redmi Note 4 (`0f6cd38c0304`): service state card
  flips live when the accessibility service is toggled, log rows appear within
  a second of capture, and the upload alarm is unaffected.

### ⚠️ Breaking Changes
- **minSdk 24.** Android 5/6 devices are no longer supported.
- **Consent re-collected** — the log viewer is a new display surface.
- **Upgrading from v3.0 in place keeps your data**, but capture stays off until
  you consent again.

### 📥 Install

```bash
adb install -r smart-keylogger-3.1.apk
```

Open the app, read the disclosure, tick consent, enable the accessibility
service, then use the bottom bar: **Home** for status, **Logs** for the live
view, **Settings** for the destination and cadence.

**Full Changelog**: https://github.com/Praveensenpai/Smart-Keylogger/compare/v3.0...v3.1
