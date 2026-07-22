# Example app — cordova-outsystems-local-notifications

Minimal Cordova app that exercises every plugin method (one button per method,
plus `localNotificationReceived` / `localNotificationActionPerformed` listeners).
The plugin is consumed from the repo root via `file:..` (see `package.json`).

## Run

```
cordova platform add android    # and/or: cordova platform add ios
cordova run android             # or ios  (device/emulator)
```
