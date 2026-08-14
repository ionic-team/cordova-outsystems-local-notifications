import Foundation
import UserNotifications

/// OutSystems structured error codes (`OS-PLUG-LNOT-NNNN`). Identical codes and
/// messages to the Capacitor plugin so both share one error contract.
enum LocalNotificationsError: Error {
    case invalidNotificationsArray
    case missingIdentifier
    case contentBuildFailed
    case triggerBuildFailed
    case notificationsDisabled
    case invalidColor
    case invalidRemoveList
    case missingIds
    case scheduleFailed
    case permissionRequestFailed

    var code: String {
        switch self {
        case .invalidNotificationsArray: return "OS-PLUG-LNOT-0001"
        case .missingIdentifier: return "OS-PLUG-LNOT-0002"
        case .contentBuildFailed: return "OS-PLUG-LNOT-0003"
        case .triggerBuildFailed: return "OS-PLUG-LNOT-0004"
        case .notificationsDisabled: return "OS-PLUG-LNOT-0005"
        case .invalidColor: return "OS-PLUG-LNOT-0006"
        case .invalidRemoveList: return "OS-PLUG-LNOT-0011"
        case .missingIds: return "OS-PLUG-LNOT-0012"
        case .scheduleFailed: return "OS-PLUG-LNOT-0010"
        case .permissionRequestFailed: return "OS-PLUG-LNOT-0013"
        }
    }

    var message: String {
        switch self {
        case .invalidNotificationsArray: return "Must provide a notifications array as the notifications option."
        case .missingIdentifier: return "Notification is missing an identifier."
        case .contentBuildFailed: return "Unable to build the notification content."
        case .triggerBuildFailed: return "Unable to create the notification, trigger construction failed."
        case .notificationsDisabled: return "Notifications are not enabled on this device."
        case .invalidColor: return "Invalid color provided. Must be a hex string (e.g. #ff0000)."
        case .invalidRemoveList: return "Expected notifications to be a list of notification objects."
        case .missingIds: return "Must provide an ids array."
        case .scheduleFailed: return "Unable to schedule the notification."
        case .permissionRequestFailed: return "Unable to request notification permission."
        }
    }

    var json: [String: String] {
        return ["code": code, "message": message]
    }
}

/// Notification content / trigger builder. Ported from the Capacitor plugin
/// (JSObject → `[String: Any]`); no Cordova or Capacitor imports.
public class LocalNotifications {

    /// Build the content for a notification, including the additive `badge`.
    func makeNotificationContent(_ notification: [String: Any]) throws -> UNNotificationContent {
        guard let title = notification["title"] as? String else {
            throw LocalNotificationsError.contentBuildFailed
        }
        guard let body = notification["body"] as? String else {
            throw LocalNotificationsError.contentBuildFailed
        }

        let schedule = notification["schedule"] ?? [:]
        let content = UNMutableNotificationContent()
        content.title = NSString.localizedUserNotificationString(forKey: title, arguments: nil)
        content.body = NSString.localizedUserNotificationString(forKey: body, arguments: nil)

        // Omit `extra` entirely when not provided, rather than defaulting to an
        // empty dictionary
        var userInfo: [String: Any] = [
            "cap_schedule": schedule
        ]
        if let extra = notification["extra"] {
            userInfo["cap_extra"] = extra
        }
        content.userInfo = userInfo

        if let actionTypeId = notification["actionTypeId"] as? String {
            content.categoryIdentifier = actionTypeId
        }

        if let threadIdentifier = notification["threadIdentifier"] as? String {
            content.threadIdentifier = threadIdentifier
        }

        if #available(iOS 15.0, *) {
            if let relevanceScore = notification["relevanceScore"] as? Double {
                content.relevanceScore = relevanceScore
            }

            if let interruptionLevelString = notification["interruptionLevel"] as? String {
                switch interruptionLevelString {
                case "active": content.interruptionLevel = .active
                case "critical": content.interruptionLevel = .critical
                case "passive": content.interruptionLevel = .passive
                case "timeSensitive": content.interruptionLevel = .timeSensitive
                default: break
                }
            }
        }

        if let sound = notification["sound"] as? String, !sound.isEmpty {
            content.sound = resolveSound(sound)
        }

        if let badge = notification["badge"] as? Int {
            content.badge = NSNumber(value: badge)
        }

        return content
    }

    /// Resolve a `sound` value to a `UNNotificationSound`. OutSystems bundles the
    /// audio file into the app's web assets (`www/` for Cordova, `public/` for
    /// Capacitor), so a bare filename is searched there — matching the exact name
    /// or the hashed `<base>__<hash>.<ext>` variant the build may emit — and the
    /// folder-qualified name is passed to `UNNotificationSound` (which is what it
    /// needs). Falls back to the value as a bundle-root sound name, then default.
    private func resolveSound(_ path: String) -> UNNotificationSound {
        let ext = (path as NSString).pathExtension
        let base = (path as NSString).deletingPathExtension
        if !ext.isEmpty, let resourcePath = Bundle.main.resourcePath {
            let fileManager = FileManager.default
            for folder in ["www", "public"] {
                let folderPath = (resourcePath as NSString).appendingPathComponent(folder)
                guard let files = try? fileManager.contentsOfDirectory(atPath: folderPath) else { continue }
                let exact = "\(base).\(ext)"
                if files.contains(exact) {
                    return UNNotificationSound(named: UNNotificationSoundName("\(folder)/\(exact)"))
                }
                let pattern = "^\(NSRegularExpression.escapedPattern(for: base))__.+\\.\(NSRegularExpression.escapedPattern(for: ext))$"
                if let match = files.first(where: { $0.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil }) {
                    return UNNotificationSound(named: UNNotificationSoundName("\(folder)/\(match)"))
                }
            }
        }
        return UNNotificationSound(named: UNNotificationSoundName(path))
    }

    /// Build a notification trigger from the Capacitor `schedule` shape.
    func handleScheduledNotification(_ schedule: [String: Any]) throws -> UNNotificationTrigger? {
        var at: Date?
        if let scheduleDate = schedule["at"] as? Date {
            at = scheduleDate
        } else if let dateString = schedule["at"] as? String {
            at = LocalNotifications.parseISODate(dateString)
        }
        let every = schedule["every"] as? String
        let count = schedule["count"] as? Int ?? 1
        let on = schedule["on"] as? [String: Any]
        let repeats = schedule["repeats"] as? Bool ?? false

        if let at = at {
            let dateInfo = Foundation.Calendar.current.dateComponents(in: TimeZone.current, from: at)

            if dateInfo.date! < Date() {
                // Already in the past — deliver immediately (a nil trigger delivers
                // right away) instead of rejecting. For `repeats`, the series isn't
                // re-registered afterward: the only interval this feature has is the
                // gap between call time and `at`, and once `at` is stale that gap is
                // gone — there's no way to recover what cadence was intended.
                return nil
            }

            let dateInterval = DateInterval(start: Date(), end: dateInfo.date!)

            if repeats && dateInterval.duration < 60 {
                throw LocalNotificationsError.triggerBuildFailed
            }

            return UNTimeIntervalNotificationTrigger(timeInterval: dateInterval.duration, repeats: repeats)
        }

        if let on = on {
            let dateComponents = getDateComponents(on)
            return UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: true)
        }

        if let every = every {
            if let repeatDateInterval = getRepeatDateInterval(every, count) {
                // A repeating UNTimeIntervalNotificationTrigger requires an interval of at
                // least 60s (a shorter one is an uncaught exception, not a catchable error).
                // `every: "second"` (or a low count) resolves under that, so clamp up to the
                // platform minimum instead of crashing.
                let interval = max(repeatDateInterval.duration, 60)
                return UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: true)
            }
        }

        return nil
    }

    func getDateComponents(_ at: [String: Any]) -> DateComponents {
        var dateInfo = DateComponents()
        if let year = at["year"] as? Int { dateInfo.year = year }
        if let month = at["month"] as? Int { dateInfo.month = month }
        if let day = at["day"] as? Int { dateInfo.day = day }
        if let hour = at["hour"] as? Int { dateInfo.hour = hour }
        if let minute = at["minute"] as? Int { dateInfo.minute = minute }
        if let second = at["second"] as? Int { dateInfo.second = second }
        if let weekday = at["weekday"] as? Int { dateInfo.weekday = weekday }
        return dateInfo
    }

    func getRepeatDateInterval(_ every: String, _ count: Int) -> DateInterval? {
        let cal = Foundation.Calendar.current
        let now = Date()
        switch every {
        case "year":
            return DateInterval(start: now, end: cal.date(byAdding: .year, value: count, to: now)!)
        case "month":
            return DateInterval(start: now, end: cal.date(byAdding: .month, value: count, to: now)!)
        case "two-weeks":
            return DateInterval(start: now, end: cal.date(byAdding: .weekOfYear, value: 2 * count, to: now)!)
        case "week":
            return DateInterval(start: now, end: cal.date(byAdding: .weekOfYear, value: count, to: now)!)
        case "day":
            return DateInterval(start: now, end: cal.date(byAdding: .day, value: count, to: now)!)
        case "hour":
            return DateInterval(start: now, end: cal.date(byAdding: .hour, value: count, to: now)!)
        case "minute":
            return DateInterval(start: now, end: cal.date(byAdding: .minute, value: count, to: now)!)
        case "second":
            return DateInterval(start: now, end: cal.date(byAdding: .second, value: count, to: now)!)
        default:
            return nil
        }
    }

    static func parseISODate(_ value: String) -> Date? {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.date(from: value)
    }
}
