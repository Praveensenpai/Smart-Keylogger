# CODEBASE.md: Smart-Keylogger Semantic Digest

> **Notice**: AI-optimized semantic index. No narrative prose. Keep token density high.

## 1. System Topology & Data Flow

```text
Launch -> MainActivity (single Compose host, 3-tab scaffold)
   │  no consent  -> consent disclosure UI; capture + upload both hard-off
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
- **Build**: AGP 8.7.3, Gradle 8.9, `compileSdk 35`, `targetSdk 35`, `minSdk 24`
- **UI**: Jetpack Compose (BOM 2024.09.03, Material 3), single activity, `android:Theme.Material.Light.NoActionBar`, dynamic color on S+. No `navigation-compose`; a `rememberSaveable` tab index in `AppScaffold` is the whole router. **Language is always Japanese** (v3.2): `values/strings.xml` is the Japanese source, no `values-en`; notification strings in `MonitoringNotification` are translated in code. Log-line event markers (`TEXT`/`CLICKED`/`FOCUSED`) stay English — data format, not UI.
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
CURRENT_CONSENT_VERSION = 2 ; DEFAULT_INTERVAL_MS = 3_600_000L
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

### `MainActivity.kt` + `ui/AppScaffold.kt`
Role: UI · Main is the single `setContent` host. `AppScaffold` = Material 3 `Scaffold` + `NavigationBar`, three tabs (Home / Logs / Settings), `rememberSaveable` index, no navigation library.

### `core/design/Theme.kt`
Role: UI · Material 3 theme, dynamic color on S+, fixed light scheme below.

### `feature/home/*` (`HomeViewModel`, `HomeRoute`, `HomeScreen`)
Role: UI · consent disclosure (checkbox + Enable), two status cards, master upload switch (disabled while the service is off), revoke dialog. `HomeViewModel` polls `StatusRepository` on a 2 s ticker; `HomeRoute` exposes `state: StateFlow<HomeState>`.

### `data/StatusRepository.kt`
Role: data · two-level status truth: `serviceOn(ctx)` reads live `Settings.Secure.enabled_accessibility_services` (OS binding), `captureOn(ctx)` reads `Prefs.captureEnabled()` (app state). Both combine into one `Status` snapshot.

### `feature/logs/*` (`LogsViewModel`, `LogsRoute`, `LogsScreen`)
Role: UI · read-only viewer. Seeds the last 1000 lines once (newest archive -> `pending.txt` -> newest outbox), then `LogRepository.LogTailer` follows `pending.txt` on a 1 s byte-offset ticker into a 1000-slot ring. LazyColumn autoscrolls while pinned to the bottom; disclaimer text states uploads still send full files.

### `data/LogRepository.kt`
Role: data · pure-Kotlin tailing primitives (unit-tested, no Android deps):
```kotlin
LineRing(capacity)          // 1000; push() overwrites oldest
tailLines(file, n)          // seed read
LogTailer                   // resume(offset) / poll() -> (lines, newOffset)
                            // offset resets to 0 on shrink, rename, delete
readFrom(ctx): source list  // archive(s) -> pending.txt -> outbox/
```

### `feature/settings/*` (`SettingsViewModel`, `SettingsRoute`, `SettingsScreen`, `SettingsFormFields`)
Role: UI · replaces the deleted `SettingsActivity`. Upload toggle, bot token field (`PasswordVisualTransformation` **plus** `semantics { password() }` so the capture guard skips it), Chat ID, interval, blocklist, Save, Delete all. Saved via `Prefs.setDestination`/`setIntervalMs`/`setBlocklist`.

### `Prefs.kt` / `KeyloggerService.kt` / `UploadPipeline.kt` / `TelegramConfig.kt` / `MonitoringNotification.kt` / `ControlReceiver.kt` / `AlarmScheduler.kt` / `UploadReceiver.kt`
Role: unchanged from v3.0. `Prefs.CURRENT_CONSENT_VERSION = 2` (log viewer is a new display surface, so upgrades re-prompt). `UploadPipeline`/`AlarmScheduler` are untouched; the viewer is strictly read-only.

## 4. Verification Evidence (device: Redmi Note 4 / mido)

| Control | Test | Result |
|---|---|---|
| Consent gate | tap Enable unchecked | status unchanged, prefs absent |
| Consent upgrade | v3.0 install -> v3.1 in-place | consent_version 1 treated stale, disclosure re-shown, re-consent records version 2 |
| Capture | type in EditText | `FOCUSED` + `TEXT` lines written |
| Password guard | type `WifiSecret99` into a `password=true` field, with a same-session control typing `poscontrol7` into a non-password field | secret NOT FOUND in `pending.txt`, daily archive, or `outbox/` (no file created at all); control **WAS** captured (`TEXT \| poscontrol7`), proving the service was live and the guard is field-specific |
| Token masking | v3.1 Settings tab, bot token field | `PasswordVisualTransformation` + `semantics { password() }`; no capture line for its content |
| Status monitor (live flip) | unbind then rebind `enabled_accessibility_services` while Home is open | `Off` -> `On`, `Capture Paused` -> `Recording` within one 2 s poll; `Last upload` advances |
| Log viewer seed | open Logs tab after install | last 1000 lines from newest archive, incl. real `TEXT \| viewprobe42` line |
| Log viewer live tail | append `TailProbe ... livetail42` to `keylog_YYYYMMDD.txt` with the tab open | line rendered within the 1 s tick |
| Delta dedupe | `retestalpha` + `retestbeta` in Settings search | delta-only line for `retestbeta`, no full-text re-dump, `<backspace>` written |
| Pause | set paused, restart, type | zero files written; notif "Monitoring paused" |
| Resume | unpause, type | `TEXT \| finaltextcheck` written |
| Revoke | tap revoke | prefs `<map />`, files empty, notification gone |
| Unit tests | `:app:testDebugUnitTest` | 9 tests, 0 failures (LineRing 3, LogRepository 5, Example 1) |

## 5. On-Device Verification Traps (Redmi Note 4 / MIUI)

These each cost a failed test run. Read before writing any ADB assertion.

1. **`uiautomator dump` silently kills the accessibility service.** It nulls `enabled_accessibility_services`. Any dump must happen *before* the capture step, followed by a real rebind.
2. **Writing the same value to `enabled_accessibility_services` is a no-op.** `AccessibilityManagerService` skips unchanged settings, so the service never rebinds. Always clear to `""`, sleep, then set — a real transition.
3. **`dumpsys accessibility | grep 'Bound services'` cannot prove a live service.** It lists *enabled* packages even when nothing is connected. The only real signal is the `Starting service` log line from `onServiceConnected()`.
4. **MIUI exposes password plaintext to the accessibility layer.** `uiautomator` reads `text=SuperSecretPW99` on a `password="true"` node. OS masking protects nothing here; `isSensitiveField()` is the whole defense.
5. **`adb shell input text` needs a real input connection.** If `dumpsys input_method` shows `mServedView=null`, injected keys go nowhere. Confirm `mInputShown=true` and use a field whose bounds are known.
6. **Compose text fields on API 24 do not emit `TYPE_VIEW_TEXT_CHANGED`.** The v3.1 Settings tab fields produce zero text events here, so they cannot prove capture. The bot token field is additionally flagged with `semantics { password() }` so the guard skips it by design. Use the system Settings search box (`AutoCompleteTextView`, bounds `[189,110][806,205]` -> tap `(497,157)`) as the capture target; it emits text events reliably.
7. **A failed `grep` proves nothing without a control.** If no log file exists, the secret is absent for the wrong reason. Always type a control string into a non-password field in the same session and prove it *was* written.
8. **`adb screencap` serves stale frames** on this device. Do not verify UI via screenshots.
9. **Two adb endpoints are usually connected** (USB `0f6cd38c0304` and network `192.168.1.39:5555`). Bare `adb` fails with "more than one device/emulator"; always pin `adb -s 0f6cd38c0304`.
10. **The IME covers the Compose tab bar** (`mInputShown=true`). Send `KEYCODE_BACK` before tapping tabs, and re-check focus after keyboard transitions.
