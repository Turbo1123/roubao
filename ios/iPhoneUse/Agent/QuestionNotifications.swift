import Foundation
import UserNotifications

final class QuestionNotifications: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = QuestionNotifications()
    private let prefix = "roubao.question."
    private let receiptQueue = DispatchQueue(label: "roubao.question-notification-receipts")
    func configure() { UNUserNotificationCenter.current().delegate = self }

    func notify(_ question: PhoneQuestion) async -> String? {
        let center = UNUserNotificationCenter.current()
        var settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            do { _ = try await center.requestAuthorization(options: [.alert, .sound, .badge]) }
            catch { record("authorization_failed", id: question.id.uuidString); return "通知申请失败，可在系统设置中开启肉包通知。" }
            settings = await center.notificationSettings()
        }
        guard [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus) else {
            record("permission_denied", id: question.id.uuidString)
            return "通知未开启，离开肉包时可能错过询问。请在系统设置中允许通知。"
        }
        // Keep task contents and the owner's answers off the lock screen.
        let content = UNMutableNotificationContent()
        content.title = "肉包需要你的答复"
        content.body = "任务已暂停。点这里返回肉包，选择或填写答复后继续。"
        content.sound = .default
        content.badge = 1
        content.threadIdentifier = "roubao.questions"
        content.userInfo = ["roubao_question": question.id.uuidString]
        do {
            try await center.add(UNNotificationRequest(identifier: prefix + question.id.uuidString, content: content, trigger: nil))
            try? await Task.sleep(for: .milliseconds(700))
            let delivered = await center.deliveredNotifications().filter { $0.request.identifier == prefix + question.id.uuidString }.count
            record("scheduled", id: question.id.uuidString, delivered: delivered)
            return settings.alertSetting == .enabled ? nil : "系统通知提醒已关闭，请在设置中开启横幅或锁屏提醒。"
        } catch { record("schedule_failed", id: question.id.uuidString); return "通知发送失败：\(error.localizedDescription)" }
    }

    func clear(_ id: UUID) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [prefix + id.uuidString])
        center.removeDeliveredNotifications(withIdentifiers: [prefix + id.uuidString])
        Task { try? await center.setBadgeCount(0) }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .badge])
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let id = response.notification.request.content.userInfo["roubao_question"] as? String {
            record("opened", id: id)
            Task { @MainActor in PhoneAgent.shared.revealQuestion(id) }
        }
        completionHandler()
    }
    private func record(_ event: String, id: String, delivered: Int? = nil) {
        var value: [String: Any] = ["event": event, "question_id": id, "time": Date().timeIntervalSince1970]
        if let delivered { value["delivered_count"] = delivered }
        guard var data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else { return }
        data.append(0x0A)
        let receipt = data
        receiptQueue.async {
            let url = URL.documentsDirectory.appendingPathComponent("question-notifications.jsonl")
            if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
            guard let handle = try? FileHandle(forWritingTo: url) else { return }
            defer { try? handle.close() }
            _ = try? handle.seekToEnd(); try? handle.write(contentsOf: receipt)
        }
    }
}
