//
//  AgentView.swift
//  iPhoneUse
//
//  ChatGPT-style layout, after github.com/PallavAg/Apple-Intelligence-Chat:
//  bubbles for the user, plain text for the agent, a floating glass composer,
//  and past runs in a chat list that slides in from the left.
//

import SwiftUI

struct AgentView: View {
    @ObservedObject private var agent = PhoneAgent.shared
    @ObservedObject private var connection = Connection.shared
    @ObservedObject private var llm = LLMSettings.shared
    @ObservedObject private var voice = VoiceRecorder.shared
    @State private var prompt = ""
    @State private var voiceDraft = VoiceDraft()
    @FocusState private var composerFocused: Bool
    @ObservedObject private var asr = ASRSettings.shared
    @State private var openRun: RunRecord.ID? // nil = the live chat
    @State private var menuOpen = false
    @State private var drag: CGFloat = 0
    @State private var carousels = CarouselFrames()
    @State private var showSettings = false
    @State private var showShortcutsGuide = false
    @State private var showFiles = false

    var body: some View {
        GeometryReader { geo in
            let menuWidth = min(geo.size.width * 0.8, 340)
            let offset = min(max((menuOpen ? menuWidth : 0) + drag, 0), menuWidth)
            ZStack(alignment: .leading) {
                ChatList(agent: agent, width: menuWidth, openRun: openRun, select: select,
                         reuse: { prompt = $0; select(nil) }, needsSetup: needsSetup, openSettings: { showSettings = true },
                         openShortcutsGuide: { showShortcutsGuide = true })
                    .accessibilityHidden(!menuOpen)
                // The mask reaches the screen edges, so the rounded corners follow the
                // display's instead of clipping the toolbar buttons.
                chat
                    .environment(\.carouselFrames, carousels)
                    .background(Color(.systemBackground))
                    .mask { RoundedRectangle(cornerRadius: offset > 0 ? 44 : 0, style: .continuous).ignoresSafeArea() }
                    .shadow(color: .black.opacity(offset > 0 ? 0.15 : 0), radius: 12)
                    .overlay {
                        if menuOpen { Color.black.opacity(0.001).onTapGesture { setMenu(false) } }
                    }
                    .offset(x: offset)
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 20, coordinateSpace: .global)
                    .onChanged { g in
                        // A swipe that starts on a step carousel scrolls it, not the drawer.
                        if !menuOpen, carousels.frames.values.contains(where: { $0.contains(g.startLocation) }) { return }
                        if abs(g.translation.width) > abs(g.translation.height) { drag = g.translation.width }
                    }
                    .onEnded { _ in
                        let open = drag > 80 || (menuOpen && drag > -80)
                        withAnimation(.snappy) { menuOpen = open; drag = 0 }
                        if open { composerFocused = false }
                    }
            )
            .sensoryFeedback(.impact(weight: .light), trigger: menuOpen)
            // Distinct from the chat's systemBackground so the panels separate in dark mode,
            // where the shadow is invisible.
            .background(Color(.secondarySystemBackground))
        }
        .onAppear { composerFocused = true } // keyboard up at launch, ready to type
        .sheet(isPresented: $showSettings) { SettingsView() }
        .sheet(isPresented: $showFiles) { TaskFilesView() }
        .sheet(isPresented: $showShortcutsGuide) {
            NavigationStack {
                ShortcutsGuideView().toolbar { Button("完成") { showShortcutsGuide = false } }
            }
        }
        // iphoneuse://run?prompt=... starts a new session, like the Run Agent intent.
        .onOpenURL { url in
            if CellularShortcut.handle(url) { return }
            if url.scheme == "iphoneuse", url.host() == "task" {
                showSettings = false; showShortcutsGuide = false; showFiles = false; openRun = nil; setMenu(false)
                if let question = agent.pendingQuestion { agent.revealQuestion(question.id.uuidString) }
                return
            }
            guard let linked = DeepLink.prompt(from: url), !agent.isRunning else { return }
            showSettings = false
            showShortcutsGuide = false
            openRun = nil
            setMenu(false)
            agent.run(prompt: linked)
        }
        .task {
            do {
                try ASRSettings.shared.importRuntimeConfiguration()
                try llm.importRuntimeConfiguration()
                try JevSettings.shared.importRuntimeConfiguration()
            } catch { return }
            if UserDefaults.standard.bool(forKey: "visualProbe"), let config = llm.config {
                let result: String
                do { result = try await PhoneAgent.verifyVisualGrounding(config: config) }
                catch { result = "视觉验证失败：" + error.localizedDescription }
                try? result.write(to: URL.documentsDirectory.appendingPathComponent("visual-test.json"), atomically: true, encoding: .utf8)
                return
            }
            if UserDefaults.standard.bool(forKey: "asrProbe") { await ASRSettings.shared.probe(); return }
            if UserDefaults.standard.bool(forKey: "jevProbe") {
                let result: String
                do { result = try await JevSettings.shared.verify() }
                catch { result = "JEV 测试失败：\(error.localizedDescription)" }
                try? result.write(to: URL.documentsDirectory.appendingPathComponent("jev-test.txt"), atomically: true, encoding: .utf8)
                return
            }
            if UserDefaults.standard.bool(forKey: "connectionProbe") {
                await connection.connect(force: true)
                return
            }
            if UserDefaults.standard.bool(forKey: "modelProbe") {
                let result: String
                if let config = llm.config {
                    do { result = try await PhoneAgent.verifyModel(config: config) }
                    catch { result = "模型能力测试失败：\(error.localizedDescription)" }
                } else { result = "模型未配置" }
                try? result.write(to: URL.documentsDirectory.appendingPathComponent("model-test.txt"), atomically: true, encoding: .utf8)
                return
            }
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "demoChat") { return agent.runDemo() }
            #endif
            if agent.pendingQuestion != nil { agent.showWaitingActivity() }
            await connection.connect()
            agent.resumeSubmittedAnswerIfNeeded()
            guard agent.pendingQuestion == nil else { return }
            // Test hooks: `devicectl device process launch ... -agentScript "..."` (no LLM, see
            // scripts/runscript.sh) or `-agentPrompt "..."` (real run).
            let launchArgs = UserDefaults.standard.volatileDomain(forName: UserDefaults.argumentDomain)
            guard !agent.isRunning else { return }
            if let script = launchArgs["agentScript"] as? String {
                agent.runScript(script)
            } else if let text = launchArgs["followUpPrompt"] as? String, let id = launchArgs["followUpRun"] as? String, let runID = UUID(uuidString: id) {
                agent.send(text, in: runID)
            } else if UserDefaults.standard.bool(forKey: "resumeAgent") {
                agent.resumeLastTask()
            } else if let auto = launchArgs["agentPrompt"] as? String, !auto.isEmpty {
                agent.run(prompt: auto)
            }
        }
    }

    private var entries: [ChatEntry] {
        guard let openRun else { return agent.entries }
        guard let run = agent.history.first(where: { $0.id == openRun }) else { return [] }
        return run.entries ?? [ChatEntry(kind: .user, text: run.prompt), ChatEntry(kind: .assistant, text: run.log)]
    }

    /// Consecutive steps collapse into one carousel row.
    private var rows: [[ChatEntry]] {
        entries.reduce(into: []) { rows, entry in
            if entry.kind == .step, rows.last?.first?.kind == .step { rows[rows.count - 1].append(entry) } else { rows.append([entry]) }
        }
    }

    private var chat: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(rows, id: \.first!.id) { row in
                        if row[0].kind == .step { StepCarousel(steps: row) } else { ChatRow(entry: row[0]) }
                    }
                    if agent.isRunning && agent.question == nil && openRun == nil { PulsingDots() }
                }
                .padding()
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .overlay {
                if entries.isEmpty { suggestions }
            }
            // Floats over the chat: it changes size while connecting at launch, and as part of
            // the bottom inset it made the suggestions jump.
            .overlay(alignment: .top) {
                if !isConnected {
                    ConnectionStatus(connection: connection, openSettings: { showSettings = true })
                        .padding(.horizontal, 16)
                        .frame(minHeight: 40)
                        .glass(in: Capsule())
                        .padding(.horizontal, 12)
                        .padding(.top, 4)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.snappy, value: connection.step)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 8) {
                    if let record = agent.resumeNotice(in: openRun ?? agent.conversationID) {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Label("本对话有未完成的任务", systemImage: "pause.circle").font(.headline)
                                Spacer()
                                Button { agent.dismissResumeNotice(record.id) } label: { Image(systemName: "xmark") }
                                    .accessibilityLabel("关闭继续任务提示")
                            }
                            Text(record.prompt).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                            Text("已执行步骤、已查资料和截图会保留，从中断处继续。").font(.caption).foregroundStyle(.secondary)
                            Button("继续任务") { agent.resumeTask(record.id); openRun = nil }.buttonStyle(.borderedProminent)
                        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18)).padding(.horizontal, 12)
                    }
                    if let report = agent.report(in: openRun ?? agent.conversationID), agent.pendingQuestion == nil {
                        HStack {
                            Button { showFiles = true } label: { Label("查看已生成的 PDF", systemImage: "doc.richtext") }
                            Spacer()
                            ShareLink(item: report) { Image(systemName: "square.and.arrow.up") }
                        }.padding(.horizontal, 20)
                    }
                    if let question = agent.pendingQuestion {
                        QuestionCard(question: question, notice: agent.questionNotice, submit: agent.answer, cancel: agent.stop)
                            .id(question.id).padding(.horizontal, 12)
                    }
                    if let error = voice.error {
                        Text(error).font(.footnote).foregroundStyle(.red).padding(.horizontal, 20)
                    }
                    if agent.isRunning && agent.question == nil {
                        Button { agent.stop() } label: {
                            Label("停止任务", systemImage: "stop.fill")
                                .font(.title3.bold())
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.haptic(.borderedProminent))
                        .buttonBorderShape(.capsule)
                        .tint(.red)
                        .padding(.horizontal, 12)
                        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { agent.setProtectedStopFrame($0) }
                        .onDisappear { agent.setProtectedStopFrame(nil) }
                    }
                    if agent.pendingQuestion == nil { composer }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Chats", systemImage: "line.3.horizontal") { setMenu(true) }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { showFiles = true } label: { Image(systemName: "folder") }.accessibilityLabel("任务文件")
                    Button("New Chat", systemImage: "square.and.pencil") { select(nil) }
                        .disabled(openRun == nil && (agent.entries.isEmpty || agent.isRunning))
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape").overlay(alignment: .topTrailing) { if needsSetup { BadgeDot() } }
                    }
                    .accessibilityLabel(needsSetup ? "Settings, setup needed" : "设置")
                }
            }
        }
    }

    private static let suggestions = [
        ("message.fill", Color.green, "打开设置，查看当前系统版本"),
        ("car.fill", Color.primary, "打开小红书，搜索 AI 实用技巧，只浏览不互动"),
        ("creditcard.fill", Color.blue, "打开地图，搜索附近的咖啡店"),
    ]

    /// Starter prompts on a new chat, sitting just above the composer.
    private var suggestions: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Self.suggestions, id: \.2) { icon, tint, text in
                Button {
                    prompt = text
                    send()
                } label: {
                    HStack(spacing: 12) {
                        // Fixed width: the symbols differ in width, the texts should still line up.
                        Image(systemName: icon).foregroundStyle(tint).frame(width: 24)
                        Text(text).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                    }
                    .font(.body)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.haptic(.plain))
            }
        }
        .padding(.horizontal, 20) // same inset as the status rows below
        .frame(maxHeight: .infinity, alignment: .bottom)
        .padding(.bottom, 8)
    }

    /// Red dot on the gear: something in the Settings checklist stops the agent from running.
    private var needsSetup: Bool {
        if llm.problem != nil { return true }
        if case .failed = connection.step { return true }
        return false
    }

    private var isConnected: Bool {
        if case .ready = connection.step { true } else { false }
    }

    private func setMenu(_ open: Bool) {
        if open { composerFocused = false }
        withAnimation(.snappy) { menuOpen = open }
    }

    /// Shows a past run, or for nil the live chat (cleared when nothing is running).
    private func select(_ id: RunRecord.ID?) {
        if id == nil, !agent.isRunning { agent.newConversation() }
        if let id { UserDefaults.standard.set(id.uuidString, forKey: "selectedConversationID") }
        openRun = id == agent.liveRun?.id ? nil : id // the live run shows as the live chat
        setMenu(false)
    }

    /// While running, the composer only answers the agent's question.
    private var composerEnabled: Bool { !agent.isRunning || agent.question != nil }

    private var canSend: Bool {
        composerEnabled && (voice.state == .recording || voice.state == .idle && !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                TextField(agent.question == nil ? (openRun != nil || agent.conversationID != nil ? "继续追问，或告诉肉包如何修改" : "告诉肉包要完成的任务") : "输入你的答复", text: $prompt, axis: .vertical)
                    .lineLimit(1...6)
                    .focused($composerFocused)
                    .frame(minHeight: 34)
                    .padding(.leading, 6)
                if voice.state != .idle {
                    HStack(spacing: 5) {
                        if voice.state == .transcribing || voice.state == .starting { ProgressView().controlSize(.mini) }
                        Text(voice.state == .recording ? "正在识别，点麦克风结束后可修改" : "正在整理识别结果…")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            if composerEnabled {
                MicButton(voice: voice, enabled: asr.available || (asr.provider == "openrouter" && llm.voiceAvailable)) { finishVoice(send: $0) }
            }
            Button(action: send) {
                Image(systemName: voice.state == .recording ? "checkmark" : "arrow.up")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color(.systemBackground))
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(canSend ? Color.primary : Color.secondary.opacity(0.4)))
            }
            .disabled(!canSend)
            .accessibilityLabel(voice.state == .recording ? "结束录音并编辑" : (agent.question == nil ? "发送" : "发送答复"))
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 10)
        .padding(.leading, 8)
        .contentShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .onTapGesture { composerFocused = true }
        .glass(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .sensoryFeedback(.start, trigger: voice.state) { _, new in new == .recording }
        .sensoryFeedback(.stop, trigger: voice.state) { old, _ in old == .recording }
        .sensoryFeedback(.error, trigger: voice.error) { _, new in new != nil }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .onChange(of: voice.state) { old, new in
            if new == .starting || (old == .idle && new == .recording) { voiceDraft.begin(prompt) }
            if old == .recording && new == .idle { composerFocused = true }
        }
        .onChange(of: voice.transcript) { _, text in
            prompt = voiceDraft.update(text, current: prompt)
        }
        .onChange(of: agent.question) { _, question in
            if question != nil { openRun = nil; setMenu(false); composerFocused = false }
        }
        .onChange(of: agent.questionPresentationID) { _, _ in
            openRun = nil; setMenu(false); showSettings = false; showShortcutsGuide = false; showFiles = false; composerFocused = false
        }
    }

    // Sending continues the selected conversation; New Chat explicitly starts fresh.
    // Mid-run, sending answers the agent's question instead.
    private func send() {
        if voice.state == .recording { return finishVoice(send: false) }
        guard canSend else { return }
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let conversation = openRun
        openRun = nil
        if agent.question != nil { agent.answer(text) } else { agent.send(text, in: conversation) }
        prompt = ""
        composerFocused = false
    }

    private func finishVoice(send: Bool) {
        Task {
            _ = await voice.finish()
            // Always leave the transcript editable. Recording never submits a task.
            composerFocused = true
        }
    }
}

/// Tap to start/stop, or hold and release to finish. Never auto-submit voice.
private struct MicButton: View {
    @ObservedObject var voice: VoiceRecorder
    let enabled: Bool
    let stop: (_ send: Bool) -> Void
    @State private var pressStart: Date?
    @State private var startedByPress = false

    var body: some View {
        Image(systemName: voice.state == .recording ? "stop.fill" : "mic")
            .font(.system(size: voice.state == .recording ? 13 : 17, weight: .semibold))
            .foregroundStyle(enabled && voice.state != .transcribing ? Color.primary : .secondary)
            .frame(width: 34, height: 34)
            .background(Circle().fill(voice.state == .recording ? Color(.secondarySystemFill) : .clear))
            .contentShape(Circle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard enabled, pressStart == nil else { return }
                    pressStart = .now
                    startedByPress = voice.state == .idle
                    if startedByPress { Task { await voice.start() } }
                }
                .onEnded { _ in
                    guard let start = pressStart else { return }
                    pressStart = nil
                    let held = Date.now.timeIntervalSince(start) > 0.4
                    if voice.state == .recording, !startedByPress || held { stop(false) }
                })
            .accessibilityLabel(voice.state == .recording ? "Stop recording" : "Record voice prompt")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction {
                if voice.state == .recording { stop(false) } else if enabled { Task { await voice.start() } }
            }
    }
}

/// Live input levels in place of the text field while recording, newest on the right.
private struct Waveform: View {
    @ObservedObject var meter: LevelMeter
    let transcribing: Bool

    var body: some View {
        // Bars sit in an overlay so a full row can't widen the composer.
        Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: 34)
            .overlay(alignment: .trailing) {
                // Always a full row (silent dots first), so bars never shift sideways as it fills.
                let row = Array(repeating: 0, count: max(0, VoiceRecorder.levelCount - meter.levels.count)) + meter.levels
                HStack(spacing: 2) {
                    ForEach(Array(row.enumerated()), id: \.offset) { _, level in
                        Capsule().frame(width: 3, height: 3 + CGFloat(level) * 24)
                    }
                }
                .foregroundStyle(transcribing ? .tertiary : .primary)
            }
            .clipped()
            .overlay { if transcribing { ProgressView() } }
            .animation(.linear(duration: 0.08), value: meter.levels)
    }
}

/// The drawer behind the chat: past runs, newest first, plus new chat and settings.
private struct ChatList: View {
    @ObservedObject var agent: PhoneAgent
    let width: CGFloat
    let openRun: RunRecord.ID?
    let select: (RunRecord.ID?) -> Void
    let reuse: (String) -> Void
    let needsSetup: Bool
    let openSettings: () -> Void
    let openShortcutsGuide: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("任务记录")
                .font(.largeTitle.bold())
                .padding(.horizontal, 20)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(agent.history) { run in
                        HStack {
                            Button { select(run.id) } label: {
                                Text(run.prompt).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                            }.buttonStyle(.haptic(.plain))
                            if agent.canResumeTask(run) {
                                Button { select(nil); agent.resumeTask(run.id) } label: { Label("继续", systemImage: "play.fill").font(.caption.bold()) }
                                    .buttonStyle(.bordered).accessibilityLabel("继续这个任务")
                            }
                        }
                        .padding(.horizontal, 12).padding(.vertical, 12)
                        .background(run.id == (openRun ?? agent.liveRun?.id) ? Color(.secondarySystemFill) : .clear, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .buttonStyle(.haptic(.plain))
                        .contextMenu {
                            if agent.canResumeTask(run) {
                                Button("继续这个任务", systemImage: "play.fill") { select(nil); agent.resumeTask(run.id) }
                            }
                            Button("再次使用", systemImage: "arrow.uturn.left") { reuse(run.prompt) }
                            Button("删除", systemImage: "trash", role: .destructive) {
                                if openRun == run.id { select(nil) }
                                agent.deleteRun(run.id)
                            }
                        }
                    }
                }
                .padding(.horizontal, 8)
            }
            .overlay {
                if agent.history.isEmpty { Text("还没有执行记录").foregroundStyle(.secondary) }
            }
            Button(action: openShortcutsGuide) {
                HStack {
                    Label("Connect shortcuts, deeplink", systemImage: "link")
                    Spacer()
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.haptic(.plain))
            .padding(.horizontal, 8)
            HStack {
                Button { select(nil) } label: {
                    Label("新任务", systemImage: "square.and.pencil")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 22)
                        .padding(.vertical, 14)
                        .background(Color.accentColor, in: Capsule())
                }
                Spacer()
                Button(action: openSettings) {
                    Image(systemName: "gearshape")
                        .font(.title3)
                        .foregroundStyle(.primary)
                        .frame(width: 50, height: 50)
                        .glass(in: Circle())
                        .overlay(alignment: .topTrailing) { if needsSetup { BadgeDot().padding(6) } }
                }
                .buttonStyle(.haptic(.plain))
                .accessibilityLabel(needsSetup ? "Settings, setup needed" : "设置")
            }
            .padding(.horizontal, 20)
        }
        .padding(.vertical, 8)
        .frame(width: width, alignment: .leading)
    }
}

private struct ChatRow: View {
    let entry: ChatEntry

    var body: some View {
        switch entry.kind {
        case .user:
            Text(entry.text)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color(.secondarySystemFill), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.leading, 48)
                .textSelection(.enabled)
        case .assistant:
            Text(entry.text).textSelection(.enabled)
        case .step:
            Text(entry.text)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
        }
    }
}

/// Where the step carousels are on screen, so the drawer swipe can leave them alone.
/// A class so scrolling (which moves them) doesn't re-render the whole chat. Carousels write
/// into it through the environment: a preference wouldn't make it out of the NavigationStack.
private final class CarouselFrames { var frames: [ChatEntry.ID: CGRect] = [:] }

private struct CarouselFramesEnvironmentKey: EnvironmentKey {
    static let defaultValue = CarouselFrames()
}

private extension EnvironmentValues {
    var carouselFrames: CarouselFrames {
        get { self[CarouselFramesEnvironmentKey.self] }
        set { self[CarouselFramesEnvironmentKey.self] = newValue }
    }
}

/// A run's steps side by side, newest on the right and kept in view.
private struct StepCarousel: View {
    let steps: [ChatEntry]
    @State private var viewing: ChatEntry.ID?
    @Environment(\.carouselFrames) private var carousels

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("执行步骤").font(.headline)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    // ponytail: non-lazy so cards keep their fade state; fine for maxSteps-sized runs.
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(steps) { step in StepCard(entry: step, open: { viewing = step.id }).id(step.id) }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                }
                .padding(.horizontal, -16) // bleed to the screen edges
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { carousels.frames[steps[0].id] = $0 }
                .onDisappear { carousels.frames[steps[0].id] = nil }
                .onAppear { proxy.scrollTo(steps.last?.id, anchor: .trailing) }
                .onChange(of: steps.count) {
                    withAnimation(.easeOut(duration: 0.4)) { proxy.scrollTo(steps.last?.id, anchor: .trailing) }
                }
            }
        }
        .padding(.vertical, 12)
        .fullScreenCover(isPresented: Binding(get: { viewing != nil }, set: { if !$0 { viewing = nil } })) {
            ShotViewer(shots: steps.filter { $0.shot == true }, selection: $viewing)
        }
    }
}

/// Full screen screenshots of a run, swiped left and right, dots on top for position.
private struct ShotViewer: View {
    let shots: [ChatEntry]
    @Binding var selection: ChatEntry.ID?
    @Environment(\.dismiss) private var dismiss
    @State private var pull: CGFloat = 0 // swipe down to close

    var body: some View {
        let index = shots.firstIndex { $0.id == selection } ?? 0
        VStack(spacing: 12) {
            ZStack {
                if shots.count <= 20 {
                    HStack(spacing: 6) {
                        ForEach(shots.indices, id: \.self) { i in
                            Circle()
                                .fill(.primary.opacity(i == index ? 1 : 0.25))
                                .frame(width: 7, height: 7)
                        }
                    }
                    .animation(.snappy, value: index)
                } else {
                    Text("\(index + 1) / \(shots.count)").font(.footnote.monospacedDigit().bold())
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .glass(in: Capsule())
            .frame(maxWidth: .infinity)
            .overlay(alignment: .trailing) {
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                        .frame(width: 44, height: 44)
                        .glass(in: Circle())
                }
                .buttonStyle(.haptic(.plain))
                .accessibilityLabel("Close")
            }
            .padding(.horizontal, 20)

            TabView(selection: $selection) {
                ForEach(shots) { shot in
                    VStack(spacing: 14) {
                        if let image = UIImage(contentsOfFile: shot.shotURL.path) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFit()
                                .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).stroke(Color(.separator), lineWidth: 0.5))
                                .shadow(color: .black.opacity(0.2), radius: 20, y: 8)
                        }
                        Text(shot.text)
                            .font(.footnote.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 32)
                    .padding(.bottom, 8)
                    .tag(Optional(shot.id))
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .offset(y: pull)
            .scaleEffect(1 - min(pull, 300) / 1500)
            .simultaneousGesture(
                DragGesture(minimumDistance: 20)
                    .onChanged { g in
                        if g.translation.height > abs(g.translation.width) { pull = g.translation.height }
                    }
                    .onEnded { g in
                        if pull > 120 || g.predictedEndTranslation.height > 400 && pull > 0 { dismiss() }
                        else { withAnimation(.snappy) { pull = 0 } }
                    }
            )
        }
        .presentationBackground(.ultraThinMaterial) // the chat shows through, blurred
    }
}

private struct StepCard: View {
    let entry: ChatEntry
    let open: () -> Void
    @State private var image: UIImage?
    @State private var shown = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if entry.shot == true {
                Group {
                    if let image { Image(uiImage: image).resizable() } else { Color(.secondarySystemFill) }
                }
                .aspectRatio(9 / 19.5, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color(.separator), lineWidth: 0.5))
                .onTapGesture { tapHaptic(); open() }
            }
            Text(entry.text)
                .font(.caption.monospaced())
                .lineLimit(3)
            if let thought = entry.thought {
                Text(thought).font(.caption).foregroundStyle(.secondary).lineLimit(4)
            }
        }
        .frame(width: 150, alignment: .leading)
        .textSelection(.enabled)
        .opacity(shown ? 1 : 0)
        .offset(y: shown ? 0 : 12)
        .scaleEffect(shown ? 1 : 0.95)
        .onAppear { withAnimation(.easeOut(duration: 0.45)) { shown = true } }
        .task { if entry.shot == true { image = UIImage(contentsOfFile: entry.shotURL.path) } }
    }
}

private struct BadgeDot: View {
    var body: some View {
        Circle().fill(.red).frame(width: 9, height: 9).offset(x: 3, y: -3)
    }
}

/// Typing indicator shown while the agent works.
private struct PulsingDots: View {
    @State private var on = false

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<3) { i in
                Circle()
                    .frame(width: 8, height: 8)
                    .foregroundStyle(.secondary)
                    .scaleEffect(on ? 1 : 0.5)
                    .opacity(on ? 1 : 0.3)
                    .animation(.easeInOut(duration: 0.6).repeatForever().delay(Double(i) * 0.2), value: on)
            }
        }
        .padding(.vertical, 6)
        .onAppear { on = true }
    }
}

private extension View {
    /// Liquid Glass on iOS 26+, frosted material before. Glass sits behind the
    /// content so it doesn't wash out solid controls (the black send button).
    @ViewBuilder func glass(in shape: some Shape) -> some View {
        if #available(iOS 26, *) {
            background { Color.clear.glassEffect(.regular, in: shape) }
        } else {
            background(.ultraThinMaterial, in: shape)
        }
    }
}

/// iOS refuses new connections to this iPhone over cellular data; see Connection.blockedByCellular.
private struct CellularHelpView: View {
    let connect: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("无需连接 Wi‑Fi。本机 VPN 已开启时，部分 iOS 版本需要短暂关闭蜂窝数据，建立手机控制连接后再恢复联网。")
                }
                Section("手动连接") {
                    Text("保持本机 VPN 开启，在控制中心暂时关闭蜂窝数据，再点下方连接。成功后重新开启蜂窝数据，供模型联网。")
                    Button("连接") { connect() }
                }
                Section {
                    Link(destination: CellularShortcut.installURL) {
                        Label("添加蜂窝连接助手", systemImage: "square.and.arrow.down")
                    }
                    Button("已添加助手，一键连接") {
                        UserDefaults.standard.set(true, forKey: "cellularHelperEnabled")
                        connect()
                    }
                } header: {
                    Text("一键连接（首次添加一次）")
                } footer: {
                    Text("添加后点击连接：肉包会通过助手临时关闭蜂窝数据，建立本机连接后恢复。若助手被取消或未能恢复，请在控制中心手动开启蜂窝数据。")
                }
            }
            .navigationTitle("蜂窝网络连接")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("关闭") { dismiss() } }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct ConnectionStatus: View {
    @ObservedObject var connection: Connection
    let openSettings: () -> Void
    @State private var showCellularHelp = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            switch connection.step {
            case .idle:
                Image(systemName: "circle").foregroundStyle(.secondary)
                Text("尚未连接").foregroundStyle(.secondary)
            case .working(let message):
                ProgressView().controlSize(.mini)
                Text(message).foregroundStyle(.secondary)
            case .ready:
                Image(systemName: "circle.fill").foregroundStyle(.green)
                Text("已连接此 iPhone")
            case .failed(let message):
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                Text(message).foregroundStyle(.secondary)
            }
            Spacer()
            if showsConnect {
                // Without a pairing file, connecting fails instantly with the same message: send to Settings instead.
                if connection.hasPairingFile {
                    Button("连接") { connect() }
                        .buttonStyle(.haptic(.bordered))
                        .controlSize(.small)
                } else {
                    Button("去设置", action: openSettings)
                        .buttonStyle(.haptic(.bordered))
                        .controlSize(.small)
                }
            }
        }
        .font(.footnote)
        .sheet(isPresented: $showCellularHelp) {
            CellularHelpView {
                showCellularHelp = false
                connect()
            }
        }
    }

    /// Opens the cellular help sheet when the attempt fails on cellular data.
    private func connect() {
        Task {
            await connection.connect(force: true, userInitiated: true)
            showCellularHelp = connection.blockedByCellular
        }
    }

    private var showsConnect: Bool {
        switch connection.step {
        case .idle, .failed: true
        case .working, .ready: false
        }
    }
}
