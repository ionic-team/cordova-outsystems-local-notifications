import Foundation
import UIKit
import UserNotifications

/// Cordova bridge for the OutSystems Local Notifications plugin. Thin framework
/// layer only — content/trigger building lives in the shared `LocalNotifications`
/// impl and event mapping in `LocalNotificationsHandler`, both ported from the
/// Capacitor plugin. Exposes the SAME method names, option keys, return shapes
/// and events as `@capacitor/local-notifications` for the shared API.
@objc(OSLocalNotificationsPlugin)
class OSLocalNotificationsPlugin: CDVPlugin {

    private let implementation = LocalNotifications()
    private let handler = LocalNotificationsHandler()

    // Persistent (keepCallback) channel used to push events to JS.
    private var eventCallbackId: String?
    // Events that arrived before the JS listener registered (e.g. cold-start tap).
    private var pendingEvents: [[String: Any]] = []

    override func pluginInitialize() {
        super.pluginInitialize()
        UNUserNotificationCenter.current().delegate = handler
        handler.onEvent = { [weak self] eventName, data in
            self?.emit(eventName, data)
        }
    }

    // MARK: - Scheduling (with implicit authorization request)

    @objc(schedule:)
    func schedule(_ command: CDVInvokedUrlCommand) {
        commandDelegate.run(inBackground: { [weak self] in
            self?.ensureAuthorization {
                self?.performSchedule(command, onlyExisting: false)
            }
        })
    }

    @objc(update:)
    func update(_ command: CDVInvokedUrlCommand) {
        commandDelegate.run(inBackground: { [weak self] in
            self?.ensureAuthorization {
                self?.performSchedule(command, onlyExisting: true)
            }
        })
    }

    private func ensureAuthorization(_ completion: @escaping () -> Void) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .notDetermined {
                self.handler.requestPermissions { _, _ in completion() }
            } else {
                completion()
            }
        }
    }

    private func performSchedule(_ command: CDVInvokedUrlCommand, onlyExisting: Bool) {
        let options = command.argument(at: 0) as? [String: Any]
        guard let notifications = options?["notifications"] as? [[String: Any]] else {
            sendError(command, .invalidNotificationsArray)
            return
        }

        if onlyExisting {
            UNUserNotificationCenter.current().getPendingNotificationRequests { pending in
                let pendingIds = Set(pending.map { $0.identifier })
                let filtered = notifications.filter { notification in
                    if let id = notification["id"] as? Int {
                        return pendingIds.contains("\(id)")
                    }
                    return false
                }
                self.scheduleNotifications(command, filtered)
            }
        } else {
            scheduleNotifications(command, notifications)
        }
    }

    private func scheduleNotifications(_ command: CDVInvokedUrlCommand, _ notifications: [[String: Any]]) {
        var ids = [String]()

        for notification in notifications {
            guard let identifier = notification["id"] as? Int else {
                sendError(command, .missingIdentifier)
                return
            }

            let content: UNNotificationContent
            do {
                content = try implementation.makeNotificationContent(notification)
            } catch {
                sendError(command, .contentBuildFailed)
                return
            }

            var trigger: UNNotificationTrigger?
            do {
                if let schedule = notification["schedule"] as? [String: Any] {
                    trigger = try implementation.handleScheduledNotification(schedule)
                }
            } catch let err as LocalNotificationsError {
                sendError(command, err)
                return
            } catch {
                sendError(command, .triggerBuildFailed)
                return
            }

            let request = UNNotificationRequest(identifier: "\(identifier)", content: content, trigger: trigger)
            handler.notificationRequestLookup[request.identifier] = notification

            UNUserNotificationCenter.current().add(request) { [weak self] error in
                if error != nil {
                    self?.sendError(command, .scheduleFailed)
                }
            }

            ids.append(request.identifier)
        }

        let ret = ids.map { ["id": Int($0) ?? -1] }
        sendOk(command, ["notifications": ret])
    }

    // MARK: - Cancel

    @objc(cancel:)
    func cancel(_ command: CDVInvokedUrlCommand) {
        let options = command.argument(at: 0) as? [String: Any]
        guard let notifications = options?["notifications"] as? [[String: Any]], notifications.count > 0 else {
            sendError(command, .invalidNotificationsArray)
            return
        }
        let ids = notifications.map { idString($0["id"]) }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
        sendOk(command, nil)
    }

    @objc(cancelAll:)
    func cancelAll(_ command: CDVInvokedUrlCommand) {
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        sendOk(command, nil)
    }

    // MARK: - Delivered notifications

    @objc(removeDeliveredNotifications:)
    func removeDeliveredNotifications(_ command: CDVInvokedUrlCommand) {
        let options = command.argument(at: 0) as? [String: Any]
        guard let notifications = options?["notifications"] as? [[String: Any]] else {
            sendError(command, .invalidRemoveList)
            return
        }
        let ids = notifications.map { idString($0["id"]) }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        sendOk(command, nil)
    }

    @objc(removeDeliveredNotificationsById:)
    func removeDeliveredNotificationsById(_ command: CDVInvokedUrlCommand) {
        let options = command.argument(at: 0) as? [String: Any]
        guard let idsArray = options?["ids"] as? [Any] else {
            sendError(command, .missingIds)
            return
        }
        let ids = idsArray.map { idString($0) }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        sendOk(command, nil)
    }

    @objc(removeAllDeliveredNotifications:)
    func removeAllDeliveredNotifications(_ command: CDVInvokedUrlCommand) {
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        if #available(iOS 16.0, *) {
            UNUserNotificationCenter.current().setBadgeCount(0)
        } else {
            DispatchQueue.main.async {
                UIApplication.shared.applicationIconBadgeNumber = 0
            }
        }
        sendOk(command, nil)
    }

    // MARK: - Queries

    @objc(getByIds:)
    func getByIds(_ command: CDVInvokedUrlCommand) {
        let options = command.argument(at: 0) as? [String: Any]
        guard let idsArray = options?["ids"] as? [Any] else {
            sendError(command, .missingIds)
            return
        }
        let wantedIds = Set(idsArray.map { idString($0) })
        let center = UNUserNotificationCenter.current()
        center.getPendingNotificationRequests { pending in
            center.getDeliveredNotifications { delivered in
                var ret = [[String: Any]]()
                for request in pending where wantedIds.contains(request.identifier) {
                    ret.append(self.handler.makeNotificationRequestJSObject(request))
                }
                for notification in delivered where wantedIds.contains(notification.request.identifier) {
                    ret.append(self.handler.makeNotificationRequestJSObject(notification.request))
                }
                self.sendOk(command, ["notifications": ret])
            }
        }
    }

    @objc(getAll:)
    func getAll(_ command: CDVInvokedUrlCommand) {
        let options = command.argument(at: 0) as? [String: Any]
        let state = options?["state"] as? String
        let center = UNUserNotificationCenter.current()

        if state == "SCHEDULED" {
            center.getPendingNotificationRequests { pending in
                let ret = pending.map { self.handler.makeNotificationRequestJSObject($0) }
                self.sendOk(command, ["notifications": ret])
            }
            return
        }
        if state == "TRIGGERED" {
            center.getDeliveredNotifications { delivered in
                let ret = delivered.map { self.handler.makeNotificationRequestJSObject($0.request) }
                self.sendOk(command, ["notifications": ret])
            }
            return
        }

        center.getPendingNotificationRequests { pending in
            center.getDeliveredNotifications { delivered in
                var ret = pending.map { self.handler.makeNotificationRequestJSObject($0) }
                ret.append(contentsOf: delivered.map { self.handler.makeNotificationRequestJSObject($0.request) })
                self.sendOk(command, ["notifications": ret])
            }
        }
    }

    // MARK: - Events

    @objc(startEventListener:)
    func startEventListener(_ command: CDVInvokedUrlCommand) {
        eventCallbackId = command.callbackId
        // CDVPluginResult's initializer is nullable on some cordova-ios versions
        // and non-optional on others; treat it as optional so it builds on both.
        let keepAlive: CDVPluginResult? = CDVPluginResult(status: CDVCommandStatus_NO_RESULT)
        if let keepAlive = keepAlive {
            keepAlive.keepCallback = NSNumber(value: true)
            commandDelegate.send(keepAlive, callbackId: command.callbackId)
        }

        // Flush any events buffered before the listener registered.
        let buffered = pendingEvents
        pendingEvents.removeAll()
        for payload in buffered {
            sendEventPayload(payload)
        }
    }

    private func emit(_ eventName: String, _ data: [String: Any]) {
        let payload: [String: Any] = ["eventName": eventName, "data": data]
        if eventCallbackId == nil {
            pendingEvents.append(payload)
            return
        }
        sendEventPayload(payload)
    }

    private func sendEventPayload(_ payload: [String: Any]) {
        guard let callbackId = eventCallbackId else { return }
        let result: CDVPluginResult? = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: payload)
        guard let result = result else { return }
        result.keepCallback = NSNumber(value: true)
        commandDelegate.send(result, callbackId: callbackId)
    }

    // MARK: - Helpers

    private func idString(_ value: Any?) -> String {
        if let intValue = value as? Int { return "\(intValue)" }
        if let numValue = value as? NSNumber { return numValue.stringValue }
        if let strValue = value as? String { return strValue }
        return ""
    }

    private func sendOk(_ command: CDVInvokedUrlCommand, _ data: [String: Any]?) {
        let result: CDVPluginResult?
        if let data = data {
            result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: data)
        } else {
            result = CDVPluginResult(status: CDVCommandStatus_OK)
        }
        if let result = result {
            commandDelegate.send(result, callbackId: command.callbackId)
        }
    }

    private func sendError(_ command: CDVInvokedUrlCommand, _ error: LocalNotificationsError) {
        let result: CDVPluginResult? = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error.json)
        if let result = result {
            commandDelegate.send(result, callbackId: command.callbackId)
        }
    }
}
