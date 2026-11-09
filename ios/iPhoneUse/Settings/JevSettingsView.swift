import SwiftUI

@MainActor final class JevSettings: ObservableObject {
    static let shared = JevSettings()
    private static let keyAccount = "jev.typesafe"
    @Published var enabled = UserDefaults.standard.bool(forKey: "jev.enabled") {
        didSet { UserDefaults.standard.set(enabled, forKey: "jev.enabled") }
    }
    @Published var model = UserDefaults.standard.string(forKey: "jev.model") ?? "jev-latest" {
        didSet { UserDefaults.standard.set(model, forKey: "jev.model") }
    }
    @Published var fastPath = (UserDefaults.standard.object(forKey: "jev.fastPath") as? Bool) ?? true {
        didSet { UserDefaults.standard.set(fastPath, forKey: "jev.fastPath") }
    }
    @Published var apiKey = Keychain.get(keyAccount) ?? "" {
        didSet { Keychain.set(apiKey, for: Self.keyAccount) }
    }
    var config: JevConfig? {
        guard enabled, !apiKey.isEmpty, UserDefaults.standard.string(forKey: "benchmarkActor") != "model" else { return nil }
        return JevConfig(apiKey: apiKey, model: model.isEmpty ? "jev-latest" : model)
    }
    var actorConfig: JevConfig? { fastPath ? config : nil }
    func importRuntimeConfiguration() throws {
        let url = URL.documentsDirectory.appendingPathComponent("jev-import.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        struct Transfer: Decodable { let apiKey: String }
        let transfer = try JSONDecoder().decode(Transfer.self, from: Data(contentsOf: url))
        apiKey = transfer.apiKey
        guard Keychain.get(Self.keyAccount) == apiKey else { throw CocoaError(.fileWriteUnknown) }
        try FileManager.default.removeItem(at: url)
        enabled = true
    }

    static func probeFrame() -> PhoneFrame {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 512, height: 512)).image { context in
            UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: 512, height: 512))
            UIColor.red.setFill(); context.fill(CGRect(x: 128, y: 128, width: 256, height: 256))
        }
        return PhoneFrame(data: image.jpegData(compressionQuality: 0.9)!, width: 512, height: 512)
    }
    func verify() async throws -> String {
        guard let config else { return "请启用 JEV 并填写 Key" }
        let result = try await TypeSafeGateway.locate(target: "the red square", frame: Self.probeFrame(), config: config)
        guard abs(result.x - 0.5) < 0.08, abs(result.y - 0.5) < 0.08 else { throw JevError.uncertain }
        return "连接成功：测试图片与目标框定位已通过"
    }
}

struct JevSettingsView: View {
    @ObservedObject private var settings = JevSettings.shared
    @State private var testing = false
    @State private var message: String?
    var body: some View {
        Form {
            Section {
                Toggle("启用 JEV 视觉定位", isOn: $settings.enabled)
                Toggle("JEV 逐步执行", isOn: $settings.fastPath).disabled(!settings.enabled)
                SecureField("TypeSafe API Key", text: $settings.apiKey)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("模型", text: $settings.model)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button(testing ? "测试中…" : "测试图片定位") {
                    testing = true
                    Task {
                        do { message = try await settings.verify() } catch { message = error.localizedDescription }
                        testing = false
                    }
                }.disabled(testing || !settings.enabled || settings.apiKey.isEmpty)
                if let message { Text(message).font(.footnote) }
            } footer: {
                Text("逐步执行：主模型先给计划，JEV 根据每张新截图选择下一步；不确定时回退主模型。关闭后仅辅助定位。截图发送到 TypeSafe，图片接口目前为实验功能。Key 仅保存在手机钥匙串。")
            }
            Link("TypeSafe 图片接口文档", destination: URL(string: "https://docs.typesafe.ai/images")!)
        }.navigationTitle("JEV 视觉定位")
    }
}
