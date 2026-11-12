## 🇯🇵 Smart Keylogger v3.2 — Japanese UI

> The capture engine, consent gate, and upload pipeline are unchanged.
> v3.2 translates every user-facing surface to Japanese: consent disclosure,
> status cards, tabs, settings, dialogs, and the ongoing notification.

### What changed
- **All UI strings are now Japanese** (`values/strings.xml`), unconditionally — the app is Japanese regardless of the device's system language. Label: スマートキーロガー.
- **Notification translated**: 監視中 / 監視 一時停止中, actions 一時停止 / 再開, channel 監視.
- Log line event markers (`TEXT` / `CLICKED` / `FOCUSED`) stay English by design: they are data format, not UI.

### Unchanged
- `UploadPipeline`, `AlarmScheduler`, `KeyloggerService`, password guard, blocklist — byte-for-byte v3.1 behavior.
- Consent version stays 2. Upgrading from v3.1 keeps your consent and data; the translated disclosure says the same thing.

### Verified
- `assembleDebug` + unit tests green (9 tests, 0 failures).
- On-device (TCL Smart TV, Android 12): install over v3.1 kept consent, Japanese UI rendered, service bound, capture probe captured while the password-flagged token field stayed excluded.
- Install: [smart-keylogger-3.2.apk](https://github.com/Praveensenpai/Smart-Keylogger/releases/latest)
