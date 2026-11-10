## ⌨️ Smart Keylogger v3.0 — Consent-Gated Rewrite

> Ground-up Kotlin rewrite. The app now refuses to capture until consent is
> recorded, excludes password fields in code, and ships with upload off and no
> credentials baked in.

### 🧭 Why a rewrite
v2.x was a capture tool with a Telegram pipe. It had no consent gate, no pause
control, no password exclusion, and baked a bot token into the build. Those are
not patches — they are a different product. v3.0 rebuilds around six controls,
each enforced in `Prefs` or `KeyloggerService` and verified on a live device.

### ✅ The Six Controls
- **Consent gate** — `Prefs.captureEnabled()` and `Prefs.uploadEnabled()` both
  require a recorded `consent_version`. With no consent there is no prefs file
  and no write path. Revoking clears prefs and deletes every log.
- **Ongoing notification** — a foreground notification with **Pause** /
  **Resume**. Paused means zero writes, proven by typing with the notification
  showing "Monitoring paused" and finding no new file.
- **Password exclusion** — `isPassword()`, `TYPE_TEXT_VARIATION_PASSWORD`,
  `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `NUMBER_VARIATION_PASSWORD`, plus a
  package blocklist preloaded with 1Password, Bitwarden, LastPass, Dashlane,
  PayPal. Any exception in the check fails closed.
- **Local-first, opt-in upload** — upload defaults off. The CI artifact has no
  token; you enter your own bot and chat ID in Settings at runtime.
- **Pause / stop / delete-all** — reachable from the notification and from
  Settings; delete-all wipes pending, outbox, archives and counters.
- **Data minimization** — 2-day archive retention (was 7), 500-char event cap,
  8 MB rotation chunks, 45 MB oversized-file parking.

### 🔧 Stack
- Kotlin 2.0.20, AGP 8.7.3, Gradle 8.9, compileSdk 35, minSdk 21.
- All Java sources removed. Every file under 400 lines.
- `AlarmManager` `RTC_WAKEUP` scheduling kept from v2.1 — still fires with the
  screen off.
- `UploadPipeline` stays a single synchronized rotate/upload/prune path.

### 📱 Device Proof (Redmi Note 4, MIUI)

```
# consent gate: Enable tapped with the box unticked
$ adb shell run-as com.bshu2.androidkeylogger ls shared_prefs
ls: shared_prefs: No such file or directory

# consent granted
consent_version=1  capture_enabled=true  consent_at=1762...

# password field: typed SuperSecretPW99, searched everywhere
$ adb shell run-as com.bshu2.androidkeylogger grep -rl SuperSecretPW99 .
(no output)

# paused from the notification, typed "pausedtest"
$ adb shell ls /sdcard/Android/data/com.bshu2.androidkeylogger/files/
(no pending.txt)

# revoked
$ cat shared_prefs/keylogger.xml
<map />
```

This build's accessibility layer **does** expose plaintext for password fields
to `uiautomator`. OS masking was not protecting anything here — the in-code
guard is what blocks the write, and the grep above is the proof.

### ⚠️ Breaking Changes
- **Consent is required.** Nothing is captured until you tick the box and tap
  Enable monitoring. There is no bypass.
- **No build-time credentials.** `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` in
  `local.properties` are ignored. Configure the destination in Settings.
- **Service name changed.** Scripted setup must now target
  `com.bshu2.androidkeylogger/com.bshu2.androidkeylogger.KeyloggerService`.
- **Retention shortened.** Archives older than 2 days are deleted.
- **Upgrading in place will fail** on a signature mismatch — uninstall the
  v2.x build first. That wipes its logs.

### 📥 Install

```bash
adb install -r smart-keylogger-3.0.apk
```

Then launch the app, read the disclosure, tick consent, and enable the
accessibility service. Upload stays off until you configure it.

**Full Changelog**: https://github.com/Praveensenpai/Smart-Keylogger/compare/v2.1...v3.0