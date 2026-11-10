# CODEBASE.md: Smart-Keylogger Semantic Digest

> **Notice**: AI-optimized semantic index. No narrative prose. Keep token density high.

## 1. System Topology & Data Flow

```text
Launch -> MainActivity (consent disclosure)
   │  no consent  -> capture + upload both hard-off
   │  "I agree"   -> Prefs.grantConsent() -> Accessibility settings
   ▼
KeyloggerService.onAccessibilityEvent()
   │  gates, in order:
   │    1. Prefs.captureEnabled()      (consent + pause switch)
   │    2. package in Prefs.blocklist()
   │    3. isSensitiveField(source)     (isPassword || inputType mask)
   ▼
   io executor -> append(keylog_YYYYMMDD.txt) + append(pending.txt)

AlarmManager (RTC_WAKEUP, Prefs.intervalMs, default 3600000)
   ▼
UploadReceiver.onReceive  -> re-arm, consent check, PARTIAL_WAKE_LOCK, Thread
   ▼
UploadPipeline.run()      @Synchronized
   ├─ rotatePending()     (split >8 MB)
   ├─ uploadOutbox()      only if Prefs.uploadEnabled() && config.isConfigured
   └─ pruneArchives()     ARCHIVE_KEEP_DAYS = 2

ControlReceiver  <- Pause/Resume action on the ongoing notification
MonitoringNotification  <- always-visible consent indicator, ONGOING, not dismissible
```

## 2. Global Constraints & Architecture Patterns

- **Language**: Kotlin 2.0.20, JVM target 17, Android
- **Build**: AGP 8.7.3, Gradle 8.9, `compileSdk 35`, `targetSdk 35`, `minSdk 21`
- **Consent is the master gate**: `Prefs.hasConsent()` is checked in the service, the pipeline, and the alarm receiver. No consent means no capture, no upload, no notification.
- **Upload is opt-in and user-configured**: destination comes from `Prefs` (settings screen), never from build-time secrets. No destination means local-only.
- **Password fields are never recorded**: `isSensitiveField()` checks `isPassword` and the `TYPE_TEXT_VARIATION_*_PASSWORD` / `TYPE_NUMBER_VARIATION_PASSWORD` mask, and **fails closed** on any exception. Verified necessary on MIUI, where the accessibility layer exposes plaintext for password nodes.
- **Visibility by construction**: `MonitoringNotification` is `setOngoing(true)`; pause/resume is reachable from it. `ControlReceiver` is `exported=false`, so only the notification PendingIntent can fire it.
- **Threading rule**: main thread only formats the line; all file/network work runs on the single `io` executor or the alarm thread. `UploadPipeline.run()` is `@Synchronized`.
- **Storage**: `getExternalFilesDir(null)`, fallback `filesDir`, then `cacheDir`

## 3. Module & Interface Skeleton

### `Prefs.kt`
Role: domain · object · single source of truth
```kotlin
CURRENT_CONSENT_VERSION = 1 ; DEFAULT_INTERVAL_MS = 3_600_000L
DEFAULT_BLOCKLIST: List<String>   // password managers + paypal
hasConsent(ctx): Boolean          // consent_version >= CURRENT
grantConsent(ctx) / revokeConsent(ctx)   // revoke clears all prefs
captureEnabled(ctx) / setCaptureEnabled(ctx, Boolean)
uploadEnabled(ctx)  / setUploadEnabled(ctx, Boolean)
intervalMs(ctx)     / setIntervalMs(ctx, Long)          // min 60_000
blocklist(ctx): Set<String> / setBlocklist / blocklistText
lastRunAt(ctx) / markRun(ctx) / resetLastRun(ctx)       // volatile mirror
botToken / chatId / setDestination(ctx, token, chat)
```

### `KeyloggerService.kt`
Role: domain + infra · extends `AccessibilityService`
- `onServiceConnected`: no consent -> cancel notification, return. Else arm alarm, post notification, run pipeline.
- `onAccessibilityEvent`: `captureEnabled` gate, blocklist gate, `isSensitiveField` gate, then build line.
- `isSensitiveField(node)`: `node.isPassword` OR inputType mask match; `catch -> true` (fail closed)
- `eventData(...)`: prefix-delta dedupe keyed by `app|title|windowId/class/viewId`; stores FULL text; `<backspace>` on deletion
- `maybeCatchUp()`: flushes an overdue cycle on any interaction
- `onDestroy`: final `rotatePending`, shutdown, 30 s await on throwaway thread
- consts: `MAX_DATA_CHARS=500`, `MAX_TRACKED_WINDOWS=50`

**Log line**
```text
<ISO8601 Z> | <model> | <app label> | <window title or class> | <TEXT|CLICKED|FOCUSED> | <text>
```

### `UploadPipeline.kt`
Role: infra · object · all blocking I/O
```kotlin
logDir(ctx): File
run(ctx)                       // @Synchronized; consent gate; rotate -> maybe upload -> prune -> markRun
append(file, line)
rotatePending(ctx) ; deleteAll(ctx)
splitPending ; writeChunk ; copyAndTruncate ; uniqueFile ; pruneArchives
uploadOutbox(ctx, config) ; sendDocument(...) ; multipartHead ; writeField
MAX_CHUNK_BYTES = 8 MB ; MAX_UPLOAD_BYTES = 45 MB ; ARCHIVE_KEEP_DAYS = 2
```

### `TelegramConfig.kt`
Role: infra · object · `Config(botToken, chatId, deviceName, intervalMs)`, `isConfigured` = both non-empty; loaded from `Prefs`.

### `MonitoringNotification.kt`
Role: infra · channel `monitoring`, id `1001`, ONGOING, title flips active/paused, Pause/Resume action -> `ControlReceiver`.

### `ControlReceiver.kt`
Role: infra · `exported=false` · handles `ACTION_PAUSE` / `ACTION_RESUME`, writes pref, refreshes notification.

### `AlarmScheduler.kt` / `UploadReceiver.kt`
Role: infra · inexact `setAndAllowWhileIdle`; receiver re-arms first, checks consent, holds 5 min wake lock.

### `MainActivity.kt` / `SettingsActivity.kt`
Role: UI · Main = disclosure + consent checkbox + enable/settings/revoke. Settings = upload toggle, token/chat, interval, blocklist, delete-all. Settings is `exported=false`.

## 4. Verification Evidence (device: Redmi Note 4 / mido)

| Control | Test | Result |
|---|---|---|
| Consent gate | tap Enable unchecked | status unchanged, prefs absent |
| Capture | type in EditText | `FOCUSED` + `TEXT` lines written |
| Password guard | type `SuperSecretPW99` into a `password=true` field, with a same-session control typing `plaincontrol1` into a non-password field | secret NOT FOUND in `pending.txt`, daily archive, `outbox/`, or private data dir (no file created at all); control **WAS** captured in `pending.txt`, proving the service was live and the guard is field-specific |
| Pause | set paused, restart, type | zero files written; notif "Monitoring paused" |
| Resume | unpause, type | `TEXT \| finaltextcheck` written |
| Revoke | tap revoke | prefs `<map />`, files empty, notification gone |

## 5. On-Device Verification Traps (Redmi Note 4 / MIUI)

These each cost a failed test run. Read before writing any ADB assertion.

1. **`uiautomator dump` silently kills the accessibility service.** It nulls `enabled_accessibility_services`. Any dump must happen *before* the capture step, followed by a real rebind.
2. **Writing the same value to `enabled_accessibility_services` is a no-op.** `AccessibilityManagerService` skips unchanged settings, so the service never rebinds. Always clear to `""`, sleep, then set — a real transition.
3. **`dumpsys accessibility | grep 'Bound services'` cannot prove a live service.** It lists *enabled* packages even when nothing is connected. The only real signal is the `Starting service` log line from `onServiceConnected()`.
4. **MIUI exposes password plaintext to the accessibility layer.** `uiautomator` reads `text=SuperSecretPW99` on a `password="true"` node. OS masking protects nothing here; `isSensitiveField()` is the whole defense.
5. **`adb shell input text` needs a real input connection.** If `dumpsys input_method` shows `mServedView=null`, injected keys go nowhere. Confirm `mInputShown=true` and use a field whose bounds are known.
6. **Messaging's composer is a bad test target** — its bounds require a dump (trap 1), which kills the service first. Use the app's own `SettingsActivity` `bot_token` EditText at `(540, 206)`, reachable via the MainActivity Settings button at `(540, 1370)` on 1080x1920.
7. **A failed `grep` proves nothing without a control.** If no log file exists, the secret is absent for the wrong reason. Always type a control string into a non-password field in the same session and prove it *was* written.
8. **`adb screencap` serves stale frames** on this device. Do not verify UI via screenshots.
