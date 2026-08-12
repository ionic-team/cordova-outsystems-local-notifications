# cordova-outsystems-local-notifications

OutSystems Cordova plugin for **Local Notifications**. It exposes the **same
public API** as [`@capacitor/local-notifications`](https://www.npmjs.com/package/@capacitor/local-notifications)
for the shared methods, over a native implementation ported from that plugin
(only the framework bridge differs). This is the Cordova half of the OutSystems
unified dual-stack plugin (RMET-5337).

## Install

```
cordova plugin add https://github.com/ionic-team/cordova-outsystems-local-notifications
```

JS namespace: `cordova.plugins.LocalNotifications`.

## API (shared with the Capacitor plugin)

| Method | Options | Returns |
| --- | --- | --- |
| `schedule(options)` | `{ notifications: LocalNotificationSchema[] }` | `{ notifications: [{ id }] }` |
| `update(options)` | `{ notifications: LocalNotificationSchema[] }` | `{ notifications: [{ id }] }` |
| `cancel(options)` | `{ notifications: [{ id }] }` | `void` |
| `cancelAll()` | — | `void` |
| `removeDeliveredNotifications(delivered)` | `{ notifications: [{ id, tag? }] }` | `void` |
| `removeDeliveredNotificationsById(options)` | `{ ids: number[] }` | `void` |
| `removeAllDeliveredNotifications()` | — | `void` |
| `getByIds(options)` | `{ ids: number[] }` | `{ notifications: LocalNotificationSchema[] }` |
| `getAll(options?)` | `{ state?: 'SCHEDULED' \| 'TRIGGERED' }` | `{ notifications: LocalNotificationSchema[] }` |
| `addListener(eventName, cb)` | `'localNotificationReceived' \| 'localNotificationActionPerformed'` | `{ remove() }` |
| `removeAllListeners()` | — | `void` |

`LocalNotificationSchema` fields honoured natively: `id`, `title`, `body`,
`schedule.{at,every,on,count,repeats,allowWhileIdle}`, `sound`, `extra`, and the
additive **`badge`** (iOS icon badge / Android `setNumber`), **`foreground`**
(iOS foreground presentation / Android heads-up priority), and the
Android-only **`isExactNotification`** (default `true`; set `false` to always
schedule this notification as an inexact alarm) / **`isExactMandatory`**
(default `false`; only enforced on `schedule()` — if `true` there and the
exact-alarm permission is denied, the whole call is rejected instead of
falling back to an inexact alarm; has no effect on `update()`, matching the
legacy plugin). A `schedule()` call that falls back to inexact (permission
denied, not mandatory) returns a non-fatal `warning`; `update()` never does,
also matching the legacy plugin.

### Permissions

Permissions are **implicit**: `schedule` / `update` request the notification
permission they need before scheduling (Android 13+ `POST_NOTIFICATIONS`; iOS
`UNUserNotificationCenter` authorization). There are no explicit
`checkPermissions` / `requestPermissions` methods on this plugin (those remain
Capacitor-only).

### Events

- `localNotificationReceived` — fired when a notification is shown/delivered.
- `localNotificationActionPerformed` — fired when a notification is tapped
  (`actionId === 'tap'`).

## Not included (Capacitor-only surface)

The following are intentionally **not** part of this Cordova plugin (they remain
in `@capacitor/local-notifications` only): `checkPermissions`,
`requestPermissions`, `areEnabled`, channels (`createChannel` / `deleteChannel` /
`listChannels`), exact-alarm settings, `registerActionTypes` (and notification
action buttons / attachments), `getPending`, and `getDeliveredNotifications`.

## Errors

Every rejection carries a structured `{ code, message }` object with an
`OS-PLUG-LNOT-NNNN` code, matching the Capacitor plugin.

## Platforms

Android and iOS. (The legacy Windows target is not carried over.)
