import SwiftUI

/// Opt-in diagnostic launch mode; never reads the pre-existing user clipboard.
struct PastePermissionProbeView: View {
    @State private var value = ""
    @State private var status = "准备测试内容…"
    private static let expected = "肉包权限框自动点击验证20261108"

    var body: some View {
        VStack(spacing: 24) {
            Text("粘贴权限验证").font(.title)
            TextField("测试输入框", text: $value).textFieldStyle(.roundedBorder)
            Text(status)
        }.padding(28)
        .task { await run() }
    }

    private func run() async {
        await Connection.shared.connect()
        do {
            try await Task.detached { try DeviceSession.shared.stageProbeText(Self.expected) }.value
            status = "读取测试内容，等待权限提示…"
            // Schedule the detector before the synchronous UIKit read: that read
            // can wait for a system alert. Detection/touch runs off the UI thread.
            let detector = Task.detached { try await Self.detectAndAllow() }
            let firstRead = UIPasteboard.general.string
            let clicked = try await detector.value
            value = firstRead ?? UIPasteboard.general.string ?? ""
            status = value == Self.expected ? "输入校验通过" : "输入未通过"
            let result: [String: Any] = ["alert_clicked_by_driver": clicked,
                "exact_field_value": value == Self.expected, "value": value,
                "note": "Own-app diagnostic with synthetic text; no pre-existing clipboard read"]
            try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys, .prettyPrinted])
                .write(to: URL.documentsDirectory.appendingPathComponent("paste-probe-result.json"), options: .atomic)
            try await Task.sleep(for: .milliseconds(500))
            try await Task.detached {
                try DeviceSession.shared.agentScreenshot(jpeg: false)
                    .write(to: URL.documentsDirectory.appendingPathComponent("paste-probe-after.png"))
            }.value
        } catch {
            status = error.localizedDescription
            try? status.write(to: URL.documentsDirectory.appendingPathComponent("paste-probe-error.txt"), atomically: true, encoding: .utf8)
        }
    }

    nonisolated private static func detectAndAllow() async throws -> Bool {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            try await Task.sleep(for: .milliseconds(500))
            let data = try DeviceSession.shared.agentScreenshot(jpeg: false)
            guard let image = UIImage(data: data)?.cgImage else { continue }
            let width = Double(image.width), height = Double(image.height)
            let lines = PhoneScreenReader.read(data, width: width, height: height)
            // Scope this click to the exact daemon source we just wrote, with an
            // exact affirmative button; "不允许粘贴" cannot match.
            guard lines.contains(where: { $0.text.contains("肉包") && $0.text.contains("dtpasteboardd") }),
                  let button = lines.first(where: {
                      ["允许粘贴", "AllowPaste"].contains($0.text.filter { !$0.isWhitespace })
                  }) else { continue }
            try data.write(to: URL.documentsDirectory.appendingPathComponent("paste-probe-before.png"))
            let point = CGPoint(x: (button.x + button.width / 2) / width, y: (button.y + button.height / 2) / height)
            try DeviceSession.shared.agentTouch(from: point, to: point)
            return true
        }
        return false
    }
}
