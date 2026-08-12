import Foundation
import UserNotifications

/// UNUserNotificationCenter delegate that maps native trigger/tap callbacks to
/// the shared `localNotificationReceived` / `localNotificationActionPerformed`
/// events. Ported from the Capacitor plugin's handler; emits through a closure
/// set by the Cordova bridge instead of Capacitor's `notifyListeners`.
public class LocalNotificationsHandler: NSObject, UNUserNotificationCenterDelegate {

    /// id -> original notification dictionary, to recover options (sound,
    /// foreground, badge) for events and queries.
    var notificationRequestLookup = [String: [String: Any]]()

    /// (eventName, data) -> emitted to JS by the bridge. Buffers via the bridge
    /// when no listener is registered yet.
    var onEvent: ((String, [String: Any]) -> Void)?

    public func requestPermissions(with completion: ((Bool, Error?) -> Void)? = nil) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.badge, .alert, .sound]) { granted, error in
            completion?(granted, error)
        }
    }

    public func checkPermissions(with completion: ((UNAuthorizationStatus) -> Void)? = nil) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            completion?(settings.authorizationStatus)
        }
    }

    // MARK: - UNUserNotificationCenterDelegate

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let notificationData = makeNotificationRequestJSObject(notification.request)
        onEvent?("localNotificationReceived", notificationData)

        if let options = notificationRequestLookup[notification.request.identifier] {
            // `foreground` takes precedence over `silent` when both are provided.
            if let foreground = options["foreground"] as? Bool {
                if foreground {
                    completionHandler(defaultPresentationOptions)
                } else {
                    completionHandler(UNNotificationPresentationOptions(rawValue: 0))
                }
                return
            }
            let silent = options["silent"] as? Bool ?? false
            if silent {
                completionHandler(UNNotificationPresentationOptions(rawValue: 0))
                return
            }
        }

        completionHandler(defaultPresentationOptions)
    }

    /// Presentation options for a foreground notification, degrading gracefully
    /// on iOS < 14 where `.banner` / `.list` are unavailable.
    private var defaultPresentationOptions: UNNotificationPresentationOptions {
        if #available(iOS 14.0, *) {
            return [.badge, .sound, .banner, .list]
        } else {
            return [.badge, .sound, .alert]
        }
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        var data = [String: Any]()
        let originalNotificationRequest = response.notification.request
        let actionId = response.actionIdentifier

        if actionId == UNNotificationDefaultActionIdentifier {
            data["actionId"] = "tap"
        } else if actionId == UNNotificationDismissActionIdentifier {
            data["actionId"] = "dismiss"
        } else {
            data["actionId"] = actionId
        }

        if let inputType = response as? UNTextInputNotificationResponse {
            data["inputValue"] = inputType.userText
        }

        data["notification"] = makeNotificationRequestJSObject(originalNotificationRequest)

        onEvent?("localNotificationActionPerformed", data)
        completionHandler()
    }

    // MARK: - Mapping

    func makeNotificationRequestJSObject(_ request: UNNotificationRequest) -> [String: Any] {
        let stored = notificationRequestLookup[request.identifier] ?? [:]
        var notification = makePendingNotificationRequestJSObject(request)
        notification["sound"] = stored["sound"] ?? ""
        notification["actionTypeId"] = request.content.categoryIdentifier
        if let badge = request.content.badge {
            notification["badge"] = badge.intValue
        }
        if let foreground = stored["foreground"] as? Bool {
            notification["foreground"] = foreground
        }
        return notification
    }

    func makePendingNotificationRequestJSObject(_ request: UNNotificationRequest) -> [String: Any] {
        var notification: [String: Any] = [
            "id": Int(request.identifier) ?? -1,
            "title": request.content.title,
            "body": request.content.body
        ]

        let userInfo = request.content.userInfo
        // `extra` is documented as `any`, not just an object. Only dictionaries
        // need per-key Date normalization; anything else is passed through as-is
        // rather than being silently dropped.
        if var extraDict = userInfo["cap_extra"] as? [String: Any] {
            for (key, value) in extraDict {
                if let date = value as? Date {
                    extraDict[key] = ISO8601DateFormatter().string(from: date)
                }
            }
            notification["extra"] = extraDict
        } else if let extraValue = userInfo["cap_extra"] {
            notification["extra"] = extraValue
        }
        if var schedule = userInfo["cap_schedule"] as? [String: Any] {
            if let date = schedule["at"] as? Date {
                schedule["at"] = ISO8601DateFormatter().string(from: date)
            }
            notification["schedule"] = schedule
        }

        return notification
    }
}
