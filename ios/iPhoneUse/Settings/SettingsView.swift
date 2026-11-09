//
//  SettingsView.swift
//  iPhoneUse
//
//  A setup checklist first (what the agent needs to run), then agent options.
//  Each checklist row opens the screen that fixes it.
//

import CoreLocation
import SwiftUI

private enum Links {
    static let repo = URL(string: "https://github.com/Turbo1123/roubao")!
    static let pairingFileGuide = URL(string: "https://github.com/Turbo1123/roubao/blob/main/ios/docs/INSTALL.md")!
    static let openRouterKeys = URL(string: "https://openrouter.ai/settings/keys")!
}

struct SettingsView: View {
    @ObservedObject private var asr = ASRSettings.shared
    @ObservedObject private var llm = LLMSettings.shared
    @ObservedObject private var connection = Connection.shared
    @ObservedObject private var location = BackgroundLocationManager.shared
    @AppStorage("keepAliveLocation") private var keepAliveLocation = true
    @Environment(\.scenePhase) private var scenePhase

    @State private var hasPairingFile = Connection.shared.hasPairingFile
    @State private var isShowingPairingFilePicker = false
    @State private var pairingMessage: (text: String, isError: Bool)?
    @State private var showClearHistoryConfirmation = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink { LLMSettingsView(settings: llm) } label: {
                        CheckRow("模型配置", detail: llm.problem ?? "\(llm.provider.title) · \(llm.model)",
                                 state: llm.problem == nil ? .done : .error)
                    }
                    Button { isShowingPairingFilePicker = true } label: {
                        CheckRow("配对文件", detail: hasPairingFile ? "已导入，点击替换" : "点击导入",
                                 state: hasPairingFile ? .done : .error)
                    }
                    .buttonStyle(.haptic(.plain))
                    if let pairingMessage {
                        Text(pairingMessage.text)
                            .font(.caption)
                            .foregroundStyle(pairingMessage.isError ? .red : .secondary)
                    }
                    NavigationLink { ConnectionSettingsView(connection: connection) } label: {
                        CheckRow("手机连接", detail: connection.step.summary, state: connection.step.checkState)
                    }
                    NavigationLink { BackgroundSettingsView(location: location) } label: {
                        CheckRow("后台运行", detail: backgroundDetail, state: backgroundState)
                    }
                } header: {
                    Text("首次设置")
                } footer: {
                    Link("如何获取配对文件", destination: Links.pairingFileGuide)
                }

                Section("语音识别") {
                    Picker("服务", selection: $asr.provider) {
                        Text("豆包 / 火山引擎").tag("volc")
                        Text("OpenRouter").tag("openrouter")
                    }
                    if asr.provider == "volc" {
                        SecureField("ASR API Key", text: $asr.apiKey)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Text("独立于主模型配置。录音在手机内转换为 16kHz 单声道 PCM，直接发送火山引擎识别；Key 保存在本机钥匙串。")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        Picker("模型", selection: $llm.voiceModel) {
                            ForEach(Set(LLMSettings.voiceModels + [llm.voiceModel]).sorted(), id: \.self) { Text($0).tag($0) }
                        }
                        Picker("语言", selection: $llm.voiceLanguage) {
                            Text("自动检测").tag("")
                            ForEach(LLMSettings.voiceLanguages, id: \.self) { Text($0).tag($0) }
                        }
                        Text("需要主模型配置选择 OpenRouter 并填写其 Key。")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }

                Section("任务执行") {
                    NavigationLink("JEV 视觉定位") { JevSettingsView() }
                    Stepper("初始步数：\(llm.maxSteps)", value: $llm.maxSteps, in: 5...200, step: 5)
                    Text("到达步数后通知并询问你；选择继续可增加50步，已有进度和截图会保留。").font(.caption).foregroundStyle(.secondary)
                    Button("恢复上次中断任务") { PhoneAgent.shared.resumeLastTask() }
                    NavigationLink("系统提示词") { SystemPromptEditor(settings: llm) }
                    NavigationLink("快捷指令与链接") { ShortcutsGuideView() }
                    Button("清空记录", role: .destructive) { showClearHistoryConfirmation = true }
                        .confirmationDialog("Delete all sessions?", isPresented: $showClearHistoryConfirmation, titleVisibility: .visible) {
                            Button("清空记录", role: .destructive) { PhoneAgent.shared.clearHistory() }
                        }
                }

                Section("关于") {
                    Link("GitHub", destination: Links.repo)
                    NavigationLink("开源致谢") { CreditsView() }
                    LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                }
            }
            .navigationTitle("设置")
            .scrollDismissesKeyboard(.interactively)
        }
        .task { await connection.connect() } // no-op when connected; retries after a failure
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { hasPairingFile = connection.hasPairingFile }
        }
        .fileImporter(
            isPresented: $isShowingPairingFilePicker,
            allowedContentTypes: PairingFileStore.supportedContentTypes
        ) { result in
            do {
                try PairingFileStore.importFromPicker(result.get())
                hasPairingFile = true
                pairingMessage = ("Imported. Connecting…", false)
                Task { await connection.connect(force: true) }
            } catch {
                pairingMessage = ("Import failed: \(error.localizedDescription)", true)
            }
        }
    }

    // Amber, not red: silent audio still keeps most runs alive.
    private var backgroundState: CheckState {
        if !keepAliveLocation && location.authorization != .denied { return .done } // audio only, chosen
        return location.authorization == .authorizedAlways ? .done : .warning
    }

    private var backgroundDetail: String {
        if !keepAliveLocation && location.authorization != .denied { return "Silent audio" }
        switch location.authorization {
        case .authorizedAlways: return "Silent audio + location"
        case .notDetermined: return "Allow location access for reliability"
        case .authorizedWhenInUse: return "Set location access to Always"
        default: return "Location refused: silent audio only"
        }
    }
}

enum CheckState { case done, warning, error, working }

private struct CheckRow: View {
    let title: String
    let detail: String
    let state: CheckState

    init(_ title: String, detail: String, state: CheckState) {
        self.title = title
        self.detail = detail
        self.state = state
    }

    var body: some View {
        HStack(spacing: 12) {
            Group {
                switch state {
                case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                case .warning: Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.orange)
                case .error: Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                case .working: ProgressView()
                }
            }
            .font(.title3)
            .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(.primary)
                Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(3)
            }
            Spacer(minLength: 0)
        }
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}

private extension Connection.Step {
    var summary: String {
        switch self {
        case .idle: "尚未连接"
        case .working(let message): message
        case .ready: VPNMode.current == .localDevVPN ? "Connected through LocalDevVPN" : "已连接此 iPhone"
        case .failed(let message): message
        }
    }

    var checkState: CheckState {
        switch self {
        case .idle: .warning
        case .working: .working
        case .ready: .done
        case .failed: .error
        }
    }
}

private struct LLMSettingsView: View {
    @ObservedObject var settings: LLMSettings
    @State private var isTesting = false
    @State private var testMessage: (text: String, isError: Bool)?

    var body: some View {
        Form {
            Section {
                Picker("服务商", selection: $settings.provider) {
                    ForEach(LLMProvider.allCases) { Text($0.title).tag($0) }
                }
                if settings.provider == .custom {
                    TextField("接口地址", text: $settings.customURL, prompt: Text(verbatim: "https://…/v1"))
                        .keyboardType(.URL)
                }
                SecureField("API Key", text: $settings.apiKey,
                            prompt: Text(settings.provider == .custom ? "API Key（本地服务可留空）" : "API Key"))
                ModelPicker(settings: settings)
            } footer: {
                if settings.provider == .openRouter {
                    Link("Get an OpenRouter key", destination: Links.openRouterKeys)
                } else {
                    Text("支持具有视觉和工具调用能力的兼容接口；Key 仅存储在本机钥匙串。")
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()

            Section {
                Button(settings.provider == .openRouter ? "测试 OpenRouter 连接" : "测试连接") {
                    isTesting = true
                    testMessage = nil
                    Task {
                        testMessage = await settings.testConnection()
                        isTesting = false
                    }
                }
                .disabled(isTesting)
                if isTesting {
                    ProgressView()
                } else if let testMessage {
                    Text(testMessage.text)
                        .font(.caption)
                        .foregroundStyle(testMessage.isError ? .red : .green)
                }
            }
        }
        .navigationTitle("模型配置")
        .scrollDismissesKeyboard(.interactively)
    }
}

private struct ConnectionSettingsView: View {
    @ObservedObject var connection: Connection
    @AppStorage(VPNMode.key) private var vpnMode = VPNMode.builtIn
    @State private var showDDIConfirmation = false
    @State private var isRedownloadingDDI = false
    @State private var ddiProgress = 0.0
    @State private var ddiMessage: (text: String, isError: Bool)?

    var body: some View {
        Form {
            Section {
                CheckRow("Status", detail: connection.step.summary, state: connection.step.checkState)
                Picker("VPN", selection: $vpnMode) {
                    Text("Built-in").tag(VPNMode.builtIn)
                    Text("LocalDevVPN").tag(VPNMode.localDevVPN)
                }
                Button("重新连接") { Task { await connection.connect(force: true, userInitiated: true) } }
                    .disabled(connection.step.checkState == .working || !connection.hasPairingFile)
                if connection.blockedByCellular {
                    Link("添加蜂窝连接助手", destination: CellularShortcut.installURL)
                    Button("已添加助手，一键连接") {
                        UserDefaults.standard.set(true, forKey: "cellularHelperEnabled")
                        Task { await connection.connect(force: true, userInitiated: true) }
                    }
                    .disabled(connection.step.checkState == .working)
                }
            } footer: {
                if vpnMode == .builtIn {
                    Text("Starts the built-in VPN, connects to this iPhone's developer services and mounts the developer disk image.")
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("For builds without the VPN entitlement (free Apple ID). Turn on LocalDevVPN, then Reconnect: it connects to this iPhone's developer services and mounts the developer disk image.")
                        Link("Get LocalDevVPN", destination: VPNMode.localDevVPNURL)
                    }
                }
            }
            Section {
                Button("Redownload Developer Disk Image") { showDDIConfirmation = true }
                    .disabled(isRedownloadingDDI)
                if isRedownloadingDDI {
                    ProgressView(value: ddiProgress)
                } else if let ddiMessage {
                    Text(ddiMessage.text)
                        .font(.caption)
                        .foregroundStyle(ddiMessage.isError ? .red : .secondary)
                }
            } footer: {
                Text("Only if mounting keeps failing, e.g. after an iOS update.")
            }
        }
        .navigationTitle("Phone Connection")
        .confirmationDialog("Redownload the developer disk image?", isPresented: $showDDIConfirmation, titleVisibility: .visible) {
            Button("Redownload", role: .destructive) { redownloadDDI() }
        }
    }

    private func redownloadDDI() {
        isRedownloadingDDI = true
        ddiMessage = nil
        Task {
            do {
                try await DeveloperDiskImageService.shared.redownload { progress, _ in
                    Task { @MainActor in ddiProgress = progress }
                }
                ddiMessage = ("Developer disk image downloaded.", false)
            } catch {
                ddiMessage = ("Download failed: \(error.localizedDescription)", true)
            }
            isRedownloadingDDI = false
        }
    }
}

private struct BackgroundSettingsView: View {
    @ObservedObject var location: BackgroundLocationManager
    @AppStorage("keepAliveAudio") private var keepAliveAudio = true
    @AppStorage("keepAliveLocation") private var keepAliveLocation = true

    var body: some View {
        Form {
            Section {
                LabeledContent("Location access", value: authorizationText)
                if location.authorization == .notDetermined {
                    Button("Allow Location Access") { location.requestAuthorizationIfNeeded() }
                } else if location.authorization != .authorizedAlways {
                    Button("Open iOS Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
            } footer: {
                Text("Background location only keeps the agent alive with \"Always\" access.")
            }
            Section {
                Toggle("Silent Audio", isOn: $keepAliveAudio)
                    .onChange(of: keepAliveAudio) { _, enabled in
                        if !enabled, !keepAliveLocation { keepAliveLocation = true }
                        keepAliveDidChange()
                    }
                Toggle("Background Location", isOn: $keepAliveLocation)
                    .onChange(of: keepAliveLocation) { _, enabled in
                        if enabled {
                            location.requestAuthorizationIfNeeded()
                        } else if !keepAliveAudio {
                            keepAliveAudio = true
                        }
                        keepAliveDidChange()
                    }
            } footer: {
                Text("Keeps the agent running while it uses other apps. At least one stays on.")
            }
        }
        .navigationTitle("Run in Background")
    }

    private var authorizationText: String {
        switch location.authorization {
        case .authorizedAlways: "Always"
        case .authorizedWhenInUse: "While Using"
        case .notDetermined: "Not asked"
        default: "Refused"
        }
    }

    private func keepAliveDidChange() {
        BackgroundAudioManager.shared.configurationDidChange()
        BackgroundLocationManager.shared.configurationDidChange()
    }
}

private struct SystemPromptEditor: View {
    @ObservedObject var settings: LLMSettings

    var body: some View {
        TextEditor(text: $settings.systemPrompt)
            .font(.callout)
            .padding(.horizontal)
            .navigationTitle("系统提示词")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                Button("Reset") { settings.systemPrompt = LLMSettings.defaultSystemPrompt }
                    .disabled(settings.systemPrompt == LLMSettings.defaultSystemPrompt)
            }
    }
}

private struct CreditsView: View {
    private let credits: [(name: String, detail: String, url: String)] = [
        ("iphone-use-app", "肉包 iOS 的应用与设备连接基础，AGPL-3.0。", "https://github.com/EliotAndres/iphone-use-app"),
        ("AgentRunKit", "iPhone 本机任务循环、工具审批与检查点恢复，MIT。", "https://github.com/Tom-Ryder/AgentRunKit"),
        ("SwiftOpenAI", "模型接口协议适配，MIT。", "https://github.com/jamesrochabrun/SwiftOpenAI"),
        ("StikDebug", "The tunnel, pairing file and developer disk image code is based on StikDebug by Stephen and contributors.",
         "https://github.com/StephenDev0/StikDebug"),
        ("idevice", "Rust library that talks to the iPhone's developer services.",
         "https://github.com/jkcoxson/idevice"),
        ("LocalDevVPN / StosVPN", "The loopback VPN is based on StosVPN by the SideStore Team, via LocalDevVPN.",
         "https://github.com/jkcoxson/LocalDevVPN"),
        ("ESP32 iPhone Agent by Henry Yuan", "模型配置、本地 OCR 与 JEV 视觉决策分层参考。",
         "https://github.com/onehumanbeing/esp32-iphone-agent"),
        ("DeveloperDiskImage", "Developer disk images downloaded at first launch.",
         "https://github.com/doronz88/DeveloperDiskImage"),
    ]

    var body: some View {
        List(credits, id: \.name) { credit in
            Link(destination: URL(string: credit.url)!) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(credit.name).foregroundStyle(.primary)
                    Text(credit.detail).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("开源致谢")
    }
}
