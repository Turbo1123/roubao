import Foundation

@MainActor
final class ASRSettings: ObservableObject {
    static let shared = ASRSettings()
    @Published var provider: String { didSet { UserDefaults.standard.set(provider, forKey: "asrProvider") } }
    @Published var apiKey: String { didSet { Keychain.set(apiKey, for: "asr.volc.apiKey") } }
    var available: Bool { provider == "volc" && !apiKey.isEmpty }
    private init() {
        provider = UserDefaults.standard.string(forKey: "asrProvider") ?? "volc"
        apiKey = Keychain.get("asr.volc.apiKey") ?? ""
    }
    func importRuntimeConfiguration() throws {
        let file = URL.documentsDirectory.appendingPathComponent("asr-import.json")
        guard FileManager.default.fileExists(atPath: file.path) else { return }
        struct Config: Decodable { let apiKey: String }
        let config = try JSONDecoder().decode(Config.self, from: Data(contentsOf: file))
        guard !config.apiKey.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
        apiKey = config.apiKey
        guard Keychain.get("asr.volc.apiKey") == config.apiKey else { throw CocoaError(.fileWriteUnknown) }
        provider = "volc"
        try FileManager.default.removeItem(at: file)
    }
    func probe() async {
        let input = URL.documentsDirectory.appendingPathComponent("asr-test.pcm")
        let result: String
        do {
            let pcm = try Data(contentsOf: input)
            let started = Date()
            let text = try await VolcASR.transcribe(pcm: pcm, apiKey: apiKey) { text in
                await MainActor.run { VoiceRecorder.shared.receiveProbeTranscript(text) }
            }
            result = "ASR 成功：\(text)\n耗时：\(String(format: "%.2f", Date().timeIntervalSince(started)))秒"
        } catch { result = "ASR 失败：\(error.localizedDescription)" }
        try? result.write(to: URL.documentsDirectory.appendingPathComponent("asr-test.txt"), atomically: true, encoding: .utf8)
        try? FileManager.default.removeItem(at: input)
    }
}
