//
//  PhoneAgent.swift
//  iPhoneUse
//
//  Prompt in, then an LLM drives this phone: CoreDevice screenshot -> chat
//  completion tool call -> CoreDevice tap/type/launch, until it calls `done`.
//

import ActivityKit
import AudioToolbox
import Foundation
import UIKit

struct RunRecord: Codable, Identifiable {
    var id = UUID()
    var date: Date
    var prompt: String
    var model: String
    var outcome: String
    var log: String
    var entries: [ChatEntry]? // nil for runs recorded before the chat UI
    var metrics: PhoneRunMetrics? = nil
    var taskFolder: String? = nil
}

/// One line of the chat: the user's prompt, the agent's words, or a step it took.
struct ChatEntry: Identifiable, Codable {
    enum Kind: String, Codable { case user, assistant, step }
    var id = UUID()
    let kind: Kind
    let text: String
    var thought: String? // a step's reasoning
    var shot: Bool? // a step's screenshot is at `shotURL`

    static let shotsURL = URL.documentsDirectory.appending(path: "shots")
    var shotURL: URL { Self.shotsURL.appending(path: "\(id).jpg") }
}

@MainActor
final class PhoneAgent: ObservableObject {
    static let shared = PhoneAgent()

    @Published var log = ""
    @Published var entries: [ChatEntry] = []
    @Published var isRunning = false
    /// Set while the model waits on `ask_user_question`; the composer answers it.
    @Published private(set) var question: String?
    @Published private(set) var pendingQuestion: PhoneQuestion?
    @Published private(set) var questionNotice: String?
    @Published private(set) var questionPresentationID = UUID()
    @Published private(set) var latestReport: URL?
    private var currentTurnPrompt = ""
    private var protectedStopFrame: CGRect?
    private var lastApprovalComment: String?
    private var answerWaiter: CheckedContinuation<String, Never>?
    @Published private(set) var history: [RunRecord] = []
    /// The run in progress, saved to history as it goes so a kill or reinstall mid-run keeps it.
    @Published private(set) var liveRun: RunRecord?
    @Published private(set) var conversationID: UUID?
    private var task: Task<Void, Never>?
    private var activity: Activity<AgentActivityAttributes>?
    private var activityState = AgentActivityAttributes.ContentState(step: 0, line: "Starting", running: true)

    nonisolated private static let imageWidth: CGFloat = 450
    private static let historyLimit = 50
    nonisolated private static let logURL = URL.documentsDirectory.appending(path: "agent.log")
    private static let historyURL = URL.documentsDirectory.appending(path: "history.json")

    private init() {
        history = (try? JSONDecoder().decode([RunRecord].self, from: Data(contentsOf: Self.historyURL))) ?? []
        let selected = UserDefaults.standard.string(forKey: "selectedConversationID").flatMap(UUID.init(uuidString:))
        let folder = (try? PhoneHarness.savedTask(at: Self.checkpointDirectory))?.folder
        let restored = selected.flatMap { id in history.first { $0.id == id } }
            ?? history.first { folder != nil && $0.taskFolder == folder }
        conversationID = restored?.id
        entries = restored?.entries ?? []
        log = restored?.log ?? ""
        if let saved = PhoneQuestionStore.live.load(), saved.answer == nil {
            pendingQuestion = saved.question
            question = saved.question.title
            let record = questionRecord(saved.question)
            conversationID = record?.id
            entries = record?.entries ?? []
            log = record?.log ?? ""
        }
    }

    func resumeLastTask() {
        guard !isRunning, pendingQuestion == nil, let saved = try? PhoneHarness.savedTask(at: Self.checkpointDirectory) else { return }
        run(prompt: saved.prompt, resume: true, resumeFolder: saved.folder)
    }
    func resumeTask(_ runID: UUID) {
        guard !isRunning, pendingQuestion == nil, let run = history.first(where: { $0.id == runID }) else { return }
        if let folder = run.taskFolder {
            runTask(prompt: (try? PhoneHarness.savedTask(at: Self.checkpointDirectory, folder: folder).prompt) ?? run.prompt, resumeFolder: folder)
        } else if let saved = try? PhoneHarness.savedTask(at: Self.checkpointDirectory), saved.prompt == run.prompt {
            resumeLastTask()
        }
    }
    func canResumeTask(_ run: RunRecord) -> Bool {
        guard !run.outcome.hasPrefix("done:"), !isRunning, pendingQuestion == nil else { return false }
        if let folder = run.taskFolder {
            return (try? PhoneHarness.savedTask(at: Self.checkpointDirectory, folder: folder).checkpoint) != nil
        }
        guard let saved = try? PhoneHarness.savedTask(at: Self.checkpointDirectory), saved.prompt == run.prompt,
              history.first(where: { $0.prompt == saved.prompt })?.id == run.id else { return false }
        return saved.checkpoint != nil
    }
    private func runTask(prompt: String, resumeFolder: String) { run(prompt: prompt, resume: true, resumeFolder: resumeFolder) }
    func resumeNotice(in conversation: UUID?) -> RunRecord? {
        guard let conversation, let record = history.first(where: { $0.id == conversation }), canResumeTask(record),
              !(UserDefaults.standard.stringArray(forKey: "dismissedResumeNotices") ?? []).contains(conversation.uuidString) else { return nil }
        return record
    }
    func dismissResumeNotice(_ id: UUID) {
        var ids = UserDefaults.standard.stringArray(forKey: "dismissedResumeNotices") ?? []
        if !ids.contains(id.uuidString) { ids.append(id.uuidString) }
        UserDefaults.standard.set(Array(ids.suffix(100)), forKey: "dismissedResumeNotices")
        objectWillChange.send()
    }
    func report(in conversation: UUID?) -> URL? {
        guard let conversation else { return nil }
        if conversation == conversationID, let latestReport { return latestReport }
        guard let folder = history.first(where: { $0.id == conversation })?.taskFolder,
              let data = try? Data(contentsOf: Self.checkpointDirectory.appendingPathComponent(folder).appendingPathComponent("progress.json")),
              let progress = try? JSONDecoder().decode(PhoneTaskProgress.self, from: data), let file = progress.reportFile,
              URL(fileURLWithPath: file).lastPathComponent == file else { return nil }
        let url = TaskReportRenderer.directory.appendingPathComponent(file)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }
    nonisolated private static var checkpointDirectory: URL {
        URL.applicationSupportDirectory.appendingPathComponent("Harness", isDirectory: true)
    }
    func newConversation() { guard !isRunning else { return }; UserDefaults.standard.removeObject(forKey: "selectedConversationID"); conversationID = nil; entries = []; log = ""; latestReport = nil }
    func send(_ text: String, in conversation: UUID? = nil) {
        let record = (conversation ?? conversationID).flatMap { id in history.first { $0.id == id } }
        run(prompt: text, resumeFolder: record?.taskFolder, continuing: record)
    }
    func run(prompt: String, resume: Bool = false, resumeFolder: String? = nil, continuing: RunRecord? = nil, answeredQuestion: PendingPhoneQuestion? = nil) {
        guard !isRunning, pendingQuestion == nil else { return }
        isRunning = true
        currentTurnPrompt = prompt
        let restoredID = resume ? PhoneConversationIdentity.restoredID(folder: resumeFolder, prompt: prompt,
            records: history.map { .init(id: $0.id, folder: $0.taskFolder, originalPrompt: $0.prompt, date: $0.date) }) : nil
        let restored = continuing ?? restoredID.flatMap { id in history.first { $0.id == id } }
        log = restored?.log ?? ""
        entries = restored?.entries ?? []
        let settings = LLMSettings.shared
        liveRun = restored ?? RunRecord(date: .now, prompt: prompt, model: settings.model, outcome: "running", log: "", entries: [])
        conversationID = liveRun?.id
        if let id = conversationID {
            UserDefaults.standard.set(id.uuidString, forKey: "selectedConversationID")
            UserDefaults.standard.set((UserDefaults.standard.stringArray(forKey: "dismissedResumeNotices") ?? []).filter { $0 != id.uuidString }, forKey: "dismissedResumeNotices")
        }
        latestReport = nil
        liveRun?.outcome = "running"
        saveLiveRun()
        let fallbackHistory = continuing.map { record in
            (record.entries ?? []).map { entry in
                entry.kind.rawValue + ": " + entry.text + (entry.shot == true ? " [evidence_id: \(entry.id.uuidString)]" : "")
            }.joined(separator: "\n")
        }
        startKeepAlive()
        startActivity(prompt: prompt)
        task = Task { [weak self] in
            guard let self else { return }
            let outcome: String
            if let problem = settings.problem {
                outcome = "error: \(problem)"
            } else {
                await Connection.shared.connect()
                if case .failed(let message) = Connection.shared.step {
                    outcome = "error: not connected: \(message)"
                } else if let config = settings.config {
                    DeviceSession.shared.startHeartbeat()
                    outcome = await loop(prompt: prompt, config: config, resume: resume, resumeFolder: resumeFolder, followUp: continuing != nil && !resume, fallbackHistory: fallbackHistory, answeredQuestion: answeredQuestion) // nonisolated: runs off the main actor
                    DeviceSession.shared.stopHeartbeat()
                } else {
                    outcome = "error: invalid settings"
                }
            }
            await say(outcome, as: .assistant, text: outcome.hasPrefix("done: ") ? String(outcome.dropFirst(6)) : outcome)
            saveLiveRun(outcome: outcome)
            liveRun = nil
            clearQuestion()
            // Pop on success, triple "nope" on failure; the user already knows when they hit Stop.
            if !Task.isCancelled { AudioServicesPlaySystemSound(outcome.hasPrefix("done: ") ? 1520 : 1521) }
            stopKeepAlive()
            isRunning = false
            endActivity()
        }
    }

    func setProtectedStopFrame(_ rect: CGRect?) {
        guard let rect else { protectedStopFrame = nil; return }
        let screen = UIScreen.main.bounds
        protectedStopFrame = CGRect(x: rect.minX / screen.width, y: rect.minY / screen.height, width: rect.width / screen.width, height: rect.height / screen.height)
    }
    private func rejectOwnerControl(name: String, arguments: [String: Any], frame: PhoneFrame) throws {
        guard UIApplication.shared.applicationState != .background else { return }
        let root = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
        // A presented file/share/settings sheet covers the root Stop button.
        if root?.presentedViewController != nil { return }
        if PhoneOwnerControls.blocks(name: name, arguments: arguments, frame: frame, region: protectedStopFrame) {
            throw NSError(domain: "owner-control", code: 1, userInfo: [NSLocalizedDescriptionKey: "禁止模型点击肉包的停止任务按钮。任务没有停止。请使用 list_task_files/share_task_file 处理文件，或重新定位其他目标。"])
        }
    }
    private func taskFiles() -> [PhoneTaskFile] {
        guard let record = liveRun ?? history.first(where: { $0.id == conversationID }) else { return [] }
        let folder = record.taskFolder.map { Self.checkpointDirectory.appendingPathComponent($0) }
        let sameTask = history.filter { $0.id == record.id || (record.taskFolder != nil && $0.taskFolder == record.taskFolder) }
        var references = sameTask.flatMap { $0.entries ?? [] }.map(\.text).joined(separator: "\n")
        if let folder, let data = try? Data(contentsOf: folder.appendingPathComponent("progress.json")), let progress = try? JSONDecoder().decode(PhoneTaskProgress.self, from: data), let report = progress.reportFile { references += "\n" + report }
        return TaskFileAccess.list(directory: TaskReportRenderer.directory, indexURL: folder?.appendingPathComponent("files.json"), history: references, latest: record.id == conversationID ? latestReport : nil)
    }

    func stop() {
        task?.cancel()
        clearQuestion()
        answerWaiter?.resume(returning: "")
        answerWaiter = nil
        if !isRunning { stopKeepAlive(); endActivity() }
    }

    func answer(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let pending = pendingQuestion else { return }
        do { try PhoneQuestionStore.live.save(.init(question: pending, answer: text)) }
        catch { questionNotice = "答复保存失败，请重试：\(error.localizedDescription)"; return }
        QuestionNotifications.shared.clear(pending.id)
        let display = Self.answerDisplay(text)
        entries.append(ChatEntry(kind: .user, text: display))
        saveLiveRun()
        question = nil
        pendingQuestion = nil
        questionNotice = nil
        updateActivity(line: "已收到答复，正在继续任务", step: nil, phase: "继续")
        answerWaiter?.resume(returning: text)
        answerWaiter = nil
        if !isRunning { resumeAnsweredQuestion(.init(question: pending, answer: text)) }
    }

    private static func answerDisplay(_ text: String) -> String {
        guard let data = text.data(using: .utf8), let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let values = json["answers"] as? [[String: String]] else { return text }
        return values.map { ($0["question"] ?? "") + "：" + ($0["answer"] ?? "") }.joined(separator: "\n")
    }
    private func questionRecord(_ pending: PhoneQuestion) -> RunRecord? {
        if let id = pending.conversationID { return history.first { $0.id == id } }
        if let folder = pending.taskFolder { return history.first { $0.taskFolder == folder } }
        // Legacy questions recorded the chat's original prompt. Find that chat,
        // never substitute a different global latest task.
        return history.first { $0.prompt == pending.taskPrompt }
    }
    private func resumeAnsweredQuestion(_ answered: PendingPhoneQuestion) {
        guard var record = questionRecord(answered.question) else {
            pendingQuestion = answered.question; question = answered.question.title
            questionNotice = "找不到原会话，答复已保存；没有新建其他任务。请从历史记录打开原任务。"
            return
        }
        let folder = answered.question.taskFolder ?? record.taskFolder
        guard let folder, let saved = try? PhoneHarness.savedTask(at: Self.checkpointDirectory, folder: folder), saved.checkpoint != nil else {
            pendingQuestion = answered.question; question = answered.question.title
            questionNotice = "原任务检查点暂不可用，答复已保留；没有重新开始任务。"
            return
        }
        if let answer = answered.answer {
            let display = Self.answerDisplay(answer)
            if record.entries?.last?.text != display { record.entries = (record.entries ?? []) + [.init(kind: .user, text: display)] }
        }
        pendingQuestion = nil; question = nil
        run(prompt: saved.prompt, resume: true, resumeFolder: folder, continuing: record, answeredQuestion: answered)
    }
    func resumeSubmittedAnswerIfNeeded() {
        guard !isRunning, let saved = PhoneQuestionStore.live.load(), saved.answer != nil else { return }
        resumeAnsweredQuestion(saved)
    }

    private func waitForAnswer(_ text: String) async -> String {
        await waitForAnswer(.text(text, taskPrompt: currentTurnPrompt))
    }

    private func waitForAnswer(_ incoming: PhoneQuestion) async -> String {
        var request = incoming
        request.conversationID = liveRun?.id
        request.taskFolder = liveRun?.taskFolder
        if let saved = PhoneQuestionStore.live.load(), saved.question.taskPrompt == incoming.taskPrompt, saved.question.conversationID == request.conversationID, saved.question.taskFolder == request.taskFolder,
           saved.question.title == incoming.title, saved.question.items == incoming.items {
            if let answer = saved.answer { return answer }
            request = saved.question
        }
        let id = request.id
        return await withTaskCancellationHandler {
            await withCheckedContinuation { waiter in
                if Task.isCancelled { return waiter.resume(returning: "") }
                do { try PhoneQuestionStore.live.save(.init(question: request)) }
                catch { questionNotice = "待答复状态保存失败，退出应用可能无法恢复。" }
                answerWaiter = waiter
                pendingQuestion = request
                question = request.title
                questionPresentationID = UUID()
                showWaitingActivity()
                Task {
                    let notice = await QuestionNotifications.shared.notify(request)
                    if self.pendingQuestion?.id == id { self.questionNotice = notice }
                    else { QuestionNotifications.shared.clear(id) }
                }
            }
        } onCancel: {
            Task { @MainActor in
                guard self.pendingQuestion?.id == id else { return }
                self.clearQuestion()
                self.answerWaiter?.resume(returning: "")
                self.answerWaiter = nil
            }
        }
    }

    private func clearQuestion() {
        if let id = pendingQuestion?.id { QuestionNotifications.shared.clear(id) }
        question = nil; pendingQuestion = nil; questionNotice = nil
        PhoneQuestionStore.live.clear()
    }

    func revealQuestion(_ id: String) {
        if pendingQuestion == nil, let saved = PhoneQuestionStore.live.load(), saved.answer == nil {
            pendingQuestion = saved.question; question = saved.question.title
        }
        guard pendingQuestion?.id.uuidString == id else { return }
        questionPresentationID = UUID()
    }

    func deleteRun(_ id: UUID) {
        guard id != liveRun?.id else { return }
        removeShots(history.filter { $0.id == id })
        history.removeAll { $0.id == id }
        try? JSONEncoder().encode(history).write(to: Self.historyURL, options: .atomic)
    }

    func clearHistory() {
        removeShots(history)
        history = []
        try? FileManager.default.removeItem(at: Self.historyURL)
    }

    private func saveLiveRun(outcome: String? = nil) {
        guard var run = liveRun else { return }
        if let outcome { run.outcome = outcome }
        run.log = log
        run.entries = entries
        liveRun = run
        if let i = history.firstIndex(where: { $0.id == run.id }) {
            history[i] = run
        } else {
            history.insert(run, at: 0)
            removeShots(Array(history.dropFirst(Self.historyLimit)))
            history = Array(history.prefix(Self.historyLimit))
        }
        try? JSONEncoder().encode(history).write(to: Self.historyURL, options: .atomic)
    }

    private func removeShots(_ runs: [RunRecord]) {
        for entry in runs.flatMap({ $0.entries ?? [] }) where entry.shot == true {
            try? FileManager.default.removeItem(at: entry.shotURL)
        }
    }

    // Silent audio / low-accuracy location keep us alive while other apps are in front.
    private func startKeepAlive() {
        UIApplication.shared.isIdleTimerDisabled = true
        BackgroundAudioManager.shared.requestStart()
        BackgroundLocationManager.shared.requestStart()
    }

    private func stopKeepAlive() {
        UIApplication.shared.isIdleTimerDisabled = UserDefaults.standard.string(forKey: "benchmarkActor") != nil
        BackgroundAudioManager.shared.requestStop()
        BackgroundLocationManager.shared.requestStop()
    }

    // Lock Screen / Dynamic Island mirror of the log. At most one: any leftover
    // activity (from a crash or an earlier run) is ended before a new one starts.
    private func startActivity(prompt: String) {
        for old in Activity<AgentActivityAttributes>.activities {
            Task { await old.end(nil, dismissalPolicy: .immediate) }
        }
        activityState = .init(step: 0, line: "正在连接并准备任务", running: true, phase: "准备")
        do {
            activity = try Activity.request(
                attributes: AgentActivityAttributes(prompt: prompt, startedAt: .now),
                content: ActivityContent(state: activityState, staleDate: nil)
            )
        } catch {
            appLog.error("Live Activity failed to start: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func updateActivity(line: String, step: Int?, phase: String? = nil) {
        guard let activity else { return }
        activityState.line = line
        if let step { activityState.step = step }
        if let phase { activityState.phase = phase }
        let content = ActivityContent(state: activityState, staleDate: nil)
        Task { await activity.update(content) }
    }

    private func endActivity() {
        guard let activity else { return }
        activityState.running = false
        let content = ActivityContent(state: activityState, staleDate: nil)
        self.activity = nil
        Task { await activity.end(content, dismissalPolicy: .immediate) }
    }

    func showWaitingActivity() {
        guard let question = pendingQuestion else { return }
        if activity == nil {
            if let existing = Activity<AgentActivityAttributes>.activities.first(where: { $0.attributes.prompt == question.taskPrompt }) {
                activity = existing; activityState = existing.content.state
            } else { startActivity(prompt: question.taskPrompt) }
        }
        updateActivity(line: "需要你的选择，点按返回肉包答复", step: nil, phase: "等待答复")
    }

    /// Test hook, no LLM: "open com.apple.Preferences; wait 2; tap 0.26 0.2; shot a".
    /// Coordinates are 0...1; shots land in Documents.
    nonisolated static func verifyVisualGrounding(config: LLMConfig) async throws -> String {
        let size = CGSize(width: 640, height: 1387)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let target = CGRect(x: 413, y: 991, width: 134, height: 112)
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            UIColor.blue.setFill(); ctx.fill(CGRect(x: 65, y: 167, width: 126, height: 116))
            UIColor.green.setFill(); ctx.fill(CGRect(x: 410, y: 402, width: 105, height: 140))
            UIColor.red.setFill(); ctx.fill(target)
            UIColor.orange.setFill(); ctx.fill(CGRect(x: 77, y: 824, width: 110, height: 133))
        }
        guard let data = image.jpegData(compressionQuality: 0.9) else { throw PhoneHarnessError.invalidArguments }
        let frame = PhoneFrame(data: data, width: size.width, height: size.height, evidenceID: UUID().uuidString)
        let result = try await ModelGateway.locateVisualTarget(target: "the only RED rectangle; locate its actual colored shape, not another color", frame: frame, config: config)
        let error = hypot(result.x - target.midX / size.width, result.y - target.midY / size.height)
        let receipt: [String: Any] = ["passed": error < 0.06, "normalized_error": error, "x": result.x, "y": result.y, "confidence": result.confidence, "ocr_provided": false, "phone_action_executed": false]
        return String(decoding: try JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys]), as: UTF8.self)
    }

    func runScript(_ script: String) {
        isRunning = true
        startKeepAlive()
        task = Task.detached { [weak self] in
            await Connection.shared.connect()
            let device = DeviceSession.shared
            for line in script.split(separator: ";").map({ $0.split(separator: " ").map(String.init) }) where !line.isEmpty {
                let n = line.dropFirst().compactMap { Double($0) }.map { CGFloat($0) }
                do {
                    switch line[0] {
                    case "open": try device.agentLaunch(bundleID: line[1])
                    case "tap": try device.agentTouch(from: CGPoint(x: n[0], y: n[1]), to: CGPoint(x: n[0], y: n[1]))
                    case "swipe": try device.agentTouch(from: CGPoint(x: n[0], y: n[1]), to: CGPoint(x: n[2], y: n[3]))
                    case "press": try device.agentTouch(from: CGPoint(x: n[0], y: n[1]), to: CGPoint(x: n[0], y: n[1]), holdMs: 800)
                    case "type": try device.agentType(line.dropFirst().joined(separator: " "))
                    case "replace": try device.agentType(line.dropFirst().joined(separator: " "), replace: true)
                    case "key": try device.agentEditKey(line[1])
                    case "home": try device.agentHome()
                    case "wait": try? await Task.sleep(for: .seconds(n[0]))
                    case "shot":
                        try device.agentScreenshot(jpeg: false)
                            .write(to: URL.documentsDirectory.appending(path: "\(line[1]).png"))
                    default: break
                    }
                    await self?.say("ok: \(line.joined(separator: " "))")
                } catch {
                    await self?.say("failed: \(line.joined(separator: " ")): \(error.localizedDescription)")
                }
            }
            await self?.say("script done")
            await self?.finishScript()
        }
    }

    #if DEBUG
    /// `-demoChat YES`: a fake run with placeholder screenshots and a question, to check the
    /// chat layout on the simulator (no device, no LLM).
    func runDemo() {
        isRunning = true
        entries = [ChatEntry(kind: .user, text: "Order my usual coffee from Starbucks")]
        let steps = [
            ("open_app bundle_id=com.starbucks.mystarbucks", "Opening Starbucks directly."),
            ("tap x=225 y=880", "The Order tab is at the bottom."),
            ("swipe x1=225 x2=225 y1=700 y2=300", "Scrolling to find recent orders."),
            ("tap x=120 y=410", "Two recent orders match, I need to know which one."),
            ("tap x=225 y=620", "Adding the chosen drink to the bag."),
            ("tap x=380 y=900", "Checking out."),
        ]
        task = Task {
            for (i, step) in steps.enumerated() {
                try? await Task.sleep(for: .seconds(1.2))
                if Task.isCancelled { break }
                await say(step.0, step: i + 1, text: "\(i + 1). \(step.0)", thought: step.1, image: Self.demoShot(i))
                if i == 3 {
                    let question = "Your recent orders: Oat Latte (Grande) or Cold Brew (Venti). Which one?"
                    await say("question", as: .assistant, text: question)
                    let answer = await waitForAnswer(question)
                    if Task.isCancelled { break }
                    await say("answer", as: .user, text: answer)
                }
            }
            if !Task.isCancelled { await say("done", as: .assistant, text: "Ordered, pickup in 8 minutes.") }
            isRunning = false
        }
    }

    private static func demoShot(_ i: Int) -> Data? {
        let size = CGSize(width: 450, height: 975)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor(hue: CGFloat(i) * 0.13, saturation: 0.35, brightness: 0.9, alpha: 1).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            ("\(i + 1)" as NSString).draw(at: CGPoint(x: 160, y: 380), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 160)])
        }.jpegData(compressionQuality: 0.7)
    }
    #endif

    private func finishScript() {
        stopKeepAlive()
        isRunning = false
    }

    /// Appends to the chat (as `text`, defaulting to `line`), the log and the Live Activity.
    /// A step can carry its reasoning and its screenshot (JPEG), shown together in the carousel.
    nonisolated private func say(_ line: String, step: Int? = nil, as kind: ChatEntry.Kind = .step, text: String? = nil,
                                 thought: String? = nil, image: Data? = nil, activityStatus: PhoneStepStatus? = nil, evidenceID: String? = nil) async {
        let time = Date().formatted(date: .omitted, time: .standard)
        let stamped = (thought.map { "\(time) · \($0)\n" } ?? "") + "\(time) \(line)\n"
        var entry = ChatEntry(kind: kind, text: text ?? line, thought: thought)
        if let evidenceID, let id = UUID(uuidString: evidenceID) { entry.id = id }
        if let image {
            try? FileManager.default.createDirectory(at: ChatEntry.shotsURL, withIntermediateDirectories: true)
            entry.shot = (try? image.write(to: entry.shotURL)) != nil
        }
        await MainActor.run { [entry] in
            self.log += stamped
            self.entries.append(entry)
            if let status = activityStatus { self.updateActivity(line: status.detail, step: step, phase: status.phase) }
            else if line.hasPrefix("routing:") { self.updateActivity(line: "正在核对界面并调整下一步", step: nil, phase: "调整") }
            self.saveLiveRun()
        }
        if let handle = try? FileHandle(forWritingTo: Self.logURL) {
            handle.seekToEndOfFile()
            handle.write(Data(stamped.utf8))
            try? handle.close()
        } else {
            try? Data(stamped.utf8).write(to: Self.logURL)
        }
    }

    /// Returns the outcome line for the history.
    nonisolated private func loop(prompt: String, config: LLMConfig, resume: Bool, resumeFolder: String? = nil, followUp: Bool = false, fallbackHistory: String? = nil, answeredQuestion: PendingPhoneQuestion? = nil) async -> String {
        let device = DeviceSession.shared
        await say("harness: AgentRunKit · " + (resume ? "resume" : followUp ? "follow-up" : "new task"))
        if !resume { await say("task: \(prompt)", as: .user, text: prompt) }
        do {
            let apps = try device.getAllApps()
            var runtimeConfig = config
            runtimeConfig.reviewsCompletion = true
            runtimeConfig.systemPrompt += "\n\nInstalled apps:\n" + apps.map { "\($0.value): \($0.key)" }.sorted().joined(separator: "\n")
            let callbacks = PhoneHarnessCallbacks(capture: {
                let image = try Self.shrink(device.agentScreenshot(jpeg: false), width: config.jev != nil || UserDefaults.standard.string(forKey: "benchmarkActor") != nil ? 640 : 450)
                return PhoneFrame(data: image.data, width: image.size.width, height: image.size.height,
                    text: PhoneScreenReader.read(image.data, width: image.size.width, height: image.size.height), evidenceID: UUID().uuidString)
            }, execute: { name, data, frame in
                try Task.checkCancellation()
                let args = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
                if name == "list_task_files" {
                    let files = await self.taskFiles()
                    return String(decoding: try JSONEncoder().encode(files), as: UTF8.self)
                }
                if name == "read_task_file" || name == "share_task_file" {
                    guard let id = args["id"] as? String else { throw PhoneHarnessError.invalidArguments }
                    let url = try await MainActor.run { try TaskFileAccess.resolve(id, files: self.taskFiles(), directory: TaskReportRenderer.directory) }
                    if name == "read_task_file" { return try await MainActor.run { try TaskFileAccess.read(url) } }
                    try device.agentLaunch(bundleID: Bundle.main.bundleIdentifier ?? "")
                    try await Task.sleep(for: .milliseconds(500))
                    try await TaskFileAccess.share(url)
                    let receipt: [String: Any] = ["share_sheet_presented": true, "file": url.lastPathComponent, "sent": false, "instruction": "File is attached to the iOS share sheet. Select the requested app/contact; use confirmed_action for final Send, then verify the actual sent message."]
                    return String(decoding: try JSONSerialization.data(withJSONObject: receipt), as: UTF8.self)
                }
                if name == "wait" {
                    guard let seconds = args["seconds"] as? Double, seconds.isFinite, (0.1...10).contains(seconds) else { throw PhoneHarnessError.invalidArguments }
                    try await Task.sleep(for: .seconds(seconds))
                    return "Waited without submitting input; inspect the new screen."
                }
                if name == "create_pdf_report" {
                    let report = try PhoneReport.decode(data)
                    let evidence = await MainActor.run { () -> [String: URL] in
                        let id = self.liveRun?.id
                        let records = self.history.filter { $0.id == id }
                        let shots = records.flatMap { $0.entries ?? [] }.filter { $0.shot == true }
                        return Dictionary(shots.map { ($0.id.uuidString, $0.shotURL) }, uniquingKeysWith: { first, _ in first })
                    }
                    let result = try TaskReportRenderer.create(report, evidence: evidence)
                    await MainActor.run { self.latestReport = result.url }
                    let receipt: [String: Any] = ["pdf_created": true, "file": result.url.lastPathComponent, "pages": result.pages, "location": "肉包→任务文件", "sharing": "iOS 系统分享面板"]
                    return String(decoding: try JSONSerialization.data(withJSONObject: receipt), as: UTF8.self)
                }
                if name == "ask_user_question" {
                    try device.agentLaunch(bundleID: Bundle.main.bundleIdentifier ?? "")
                    let question = try PhoneQuestion.decode(data, taskPrompt: prompt)
                    return await self.waitForAnswer(question)
                }
                if name == "locate_visual_target" {
                    guard let target = args["target"] as? String, !target.isEmpty, target.count <= 500 else { throw PhoneHarnessError.invalidArguments }
                    let located = try await ModelGateway.locateVisualTarget(target: target, frame: frame, config: config)
                    return String(decoding: try JSONEncoder().encode(located), as: UTF8.self)
                }
                if name == "tap_target" {
                    guard let target = args["target"] as? String else { throw PhoneHarnessError.invalidArguments }
                    guard let config = await MainActor.run(body: { JevSettings.shared.config }) else {
                        return "{\"executed\":false,\"reason\":\"JEV 未启用或未配置，请使用当前截图的 OCR ID 或坐标\"}"
                    }
                    var actionStarted = false
                    do {
                        let located = try await TypeSafeGateway.locate(target: target, frame: frame, config: config)
                        let fresh = try Self.shrink(device.agentScreenshot(jpeg: false))
                        guard Self.similarScreens(frame.data, fresh.data) else { throw PhoneHarnessError.staleScreen }
                        try Task.checkCancellation()
                        let point = CGPoint(x: located.x, y: located.y)
                        let hit: [String: Any] = ["x": point.x * frame.width, "y": point.y * frame.height]
                        try await self.rejectOwnerControl(name: "tap", arguments: hit, frame: frame)
                        if let label = PhoneFinalActionGuard.blockedLabel(name: "tap", arguments: hit, frame: frame) {
                            throw NSError(domain: "approval-required", code: 1, userInfo: [NSLocalizedDescriptionKey: "视觉点击不能绕过“" + label + "”的授权，请使用 confirmed_action；已有提交时先核对结果。"])
                        }
                        actionStarted = true
                        try device.agentTouch(from: point, to: point)
                        try await Task.sleep(for: .milliseconds(700))
                        return "{\"executed\":true,\"source\":\"JEV\",\"confidence\":\(located.confidence)}"
                    } catch is CancellationError { throw CancellationError() }
                    catch {
                        let receipt: [String: Any] = ["execution_status": actionStarted ? "unknown_check_screen" : "not_executed", "reason": error.localizedDescription,
                            "instruction": "Do not retry automatically. Inspect a fresh screen before selecting another action."]
                        return String(data: try JSONSerialization.data(withJSONObject: receipt), encoding: .utf8)!
                    }
                }
                if args["grounding_source"] as? String == "primary_multimodal" {
                    let fresh = try Self.shrink(device.agentScreenshot(jpeg: false), width: frame.width)
                    let current = PhoneFrame(data: fresh.data, width: fresh.size.width, height: fresh.size.height,
                        text: PhoneScreenReader.read(fresh.data, width: fresh.size.width, height: fresh.size.height))
                    guard let box = args["_visual_region"] as? [String: Double], let x = box["x"], let y = box["y"], let w = box["width"], let h = box["height"],
                          PhoneVisualFreshness.matches(region: CGRect(x: x, y: y, width: w, height: h), before: frame, current: current) else { throw PhoneHarnessError.staleScreen }
                }
                let result: String
                if name == "confirmed_action" {
                    guard let bundle = args["bundle_id"] as? String, apps[bundle] != nil,
                          let action = args["action"] as? String, ["tap", "long_press", "type", "replace_text", "press_key"].contains(action),
                          let inner = args["arguments"] as? [String: Any] else { throw PhoneHarnessError.invalidArguments }
                    try device.agentLaunch(bundleID: bundle)
                    try await Task.sleep(for: .seconds(1))
                    let fresh = try Self.shrink(device.agentScreenshot(jpeg: false), width: frame.width)
                    if let box = inner["_visual_region"] as? [String: Double], let x = box["x"], let y = box["y"], let w = box["width"], let h = box["height"] {
                        let current = PhoneFrame(data: fresh.data, width: fresh.size.width, height: fresh.size.height, text: PhoneScreenReader.read(fresh.data, width: fresh.size.width, height: fresh.size.height))
                        guard PhoneVisualFreshness.matches(region: CGRect(x: x, y: y, width: w, height: h), before: frame, current: current) else { throw PhoneHarnessError.staleScreen }
                    } else { guard Self.similarScreens(frame.data, fresh.data) else { throw PhoneHarnessError.staleScreen } }
                    try await self.rejectOwnerControl(name: action, arguments: inner, frame: frame)
                    result = try Self.perform(action, inner, imageSize: fresh.size, device: device)
                } else {
                    try await self.rejectOwnerControl(name: name, arguments: args, frame: frame)
                    result = try Self.perform(name, args, imageSize: CGSize(width: frame.width, height: frame.height), device: device, frame: frame)
                }
                try await Task.sleep(for: .milliseconds(name == "open_app" ? 1500 : (config.jev == nil ? 700 : 150)))
                return result
            }, observed: { step in
                let args = (try? JSONSerialization.jsonObject(with: step.arguments)) as? [String: Any] ?? [:]
                let text = "\(step.index) \(step.name) \(Self.compact(args))"
                var status = PhoneStepStatus.describe(name: step.name, arguments: args, visibleText: step.frame.text, apps: apps)
                if step.thought.hasPrefix("主模型单步接管") { status = .init(phase: "接管", detail: status.detail) }
                await self.say(text, step: step.index, text: text, thought: step.thought, image: step.frame.data, activityStatus: status, evidenceID: step.frame.evidenceID)
            }, approve: { raw in
                try? device.agentLaunch(bundleID: Bundle.main.bundleIdentifier ?? "")
                guard let request = try? PhoneAuthorization.Request(raw) else { return false }
                await MainActor.run { self.lastApprovalComment = nil }
                while true {
                    var question = PhoneQuestion(title: "确认执行这项操作？", items: [.init(id: "decision", prompt: request.description, options: [.init(label: "确认执行"), .init(label: "取消")])], taskPrompt: prompt, kind: "action_approval")
                    question.approvalArguments = raw
                    let answer = await self.waitForAnswer(question)
                    if Task.isCancelled { return false }
                    switch PhoneApprovalDecision.parse(answer) {
                    case .approve: return true
                    case .deny: return false
                    case .unclear:
                        await MainActor.run { self.lastApprovalComment = answer }
                        return false
                    }
                }
            }, measured: { metrics in
                try? JSONEncoder().encode(metrics).write(to: URL.documentsDirectory.appendingPathComponent("jev-run-metrics.json"), options: .atomic)
                await MainActor.run { self.liveRun?.metrics = metrics }
                await self.say("metrics: \(metrics.mode) main=\(metrics.mainCalls) jev=\(metrics.jevCalls) fallback=\(metrics.fallbacks) actions=\(metrics.actions) total=\(String(format: "%.2f", metrics.totalSeconds))s mainTime=\(String(format: "%.2f", metrics.mainSeconds))s jevTime=\(String(format: "%.2f", metrics.jevSeconds))s")
            }, jevResponse: { data in
                if let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    let receipt = root.filter { ["model", "answers", "usage"].contains($0.key) }
                    try? JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys]).write(to: URL.documentsDirectory.appendingPathComponent("jev-last-response.json"), options: .atomic)
                }
            }, routed: { message in
                await self.say("routing: " + message)
            }, budgetExceeded: { limit in
                try device.agentLaunch(bundleID: Bundle.main.bundleIdentifier ?? "")
                while true {
                    let request = PhoneQuestion(title: "已执行 \(limit) 步，是否继续？", items: [
                        .init(id: "step_budget", prompt: "进度、已查资料和截图已保存。继续会在原任务上增加50步，不会重新开始。", options: [
                            .init(label: PhoneStepBudget.continueLabel), .init(label: PhoneStepBudget.pauseLabel), .init(label: PhoneStepBudget.stopLabel)
                        ])
                    ], taskPrompt: prompt, kind: "step_budget")
                    let answer = await self.waitForAnswer(request)
                    try Task.checkCancellation()
                    let decision = PhoneStepBudget.decision(answer)
                    if case .unclear = decision {
                        await MainActor.run { PhoneQuestionStore.live.clear() }
                        continue
                    }
                    return decision
                }
            }, prepared: { saved in
                await MainActor.run { self.liveRun?.taskFolder = saved.folder; self.saveLiveRun() }
            }, answerRestored: {
                await MainActor.run {
                    if PhoneQuestionStore.live.load()?.question.id == answeredQuestion?.question.id { PhoneQuestionStore.live.clear() }
                }
            }, taskFiles: { await self.taskFiles() }, approvalComment: { await MainActor.run { self.lastApprovalComment } })
            let outcome = try await PhoneHarness.run(prompt: prompt, config: runtimeConfig, callbacks: callbacks,
                checkpointDirectory: Self.checkpointDirectory, resume: resume, resumeFolder: resumeFolder, followUp: followUp, fallbackHistory: fallbackHistory, answeredQuestion: answeredQuestion)
            if outcome.hasPrefix("done:") { try? device.agentLaunch(bundleID: Bundle.main.bundleIdentifier ?? "") }
            return outcome
        } catch is CancellationError { return "stopped" }
        catch { return "error: \(error.localizedDescription)" }
    }

    nonisolated private static func similarScreens(_ a: Data, _ b: Data) -> Bool {
        func pixels(_ data: Data) -> [UInt8]? {
            guard let image = UIImage(data: data)?.cgImage else { return nil }
            var bytes = [UInt8](repeating: 0, count: 32 * 64)
            guard let context = CGContext(data: &bytes, width: 32, height: 64, bitsPerComponent: 8,
                bytesPerRow: 32, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: 0) else { return nil }
            context.draw(image, in: CGRect(x: 0, y: 0, width: 32, height: 64))
            return bytes
        }
        guard let x = pixels(a), let y = pixels(b) else { return false }
        return zip(x, y).reduce(0.0) { $0 + abs(Double($1.0) - Double($1.1)) } / Double(x.count) < 6
    }

    nonisolated private static func perform(_ name: String, _ args: [String: Any], imageSize: CGSize, device: DeviceSession, frame: PhoneFrame? = nil) throws -> String {
        func point(_ x: String, _ y: String) throws -> CGPoint {
            guard let a = args[x] as? NSNumber, let b = args[y] as? NSNumber,
                  a.doubleValue.isFinite, b.doubleValue.isFinite,
                  a.doubleValue >= 0, a.doubleValue <= imageSize.width,
                  b.doubleValue >= 0, b.doubleValue <= imageSize.height else { throw PhoneHarnessError.invalidArguments }
            return CGPoint(x: a.doubleValue / imageSize.width, y: b.doubleValue / imageSize.height)
        }
        switch name {
        case "tap":
            let p = try point("x", "y")
            try device.agentTouch(from: p, to: p)
        case "tap_text":
            guard let id = args["id"] as? String, let line = frame?.text.first(where: { $0.id == id }) else {
                throw PhoneHarnessError.invalidArguments
            }
            let p = CGPoint(x: (line.x + line.width / 2) / imageSize.width, y: (line.y + line.height / 2) / imageSize.height)
            try device.agentTouch(from: p, to: p)
        case "long_press":
            let p = try point("x", "y")
            try device.agentTouch(from: p, to: p, holdMs: 800)
        case "swipe":
            try device.agentTouch(from: try point("x1", "y1"), to: try point("x2", "y2"))
        case "swipe_up", "swipe_down":
            let up = name == "swipe_up"
            try device.agentTouch(from: CGPoint(x: 0.5, y: up ? 0.7 : 0.3), to: CGPoint(x: 0.5, y: up ? 0.3 : 0.7))
        case "type", "replace_text":
            guard let text = args["text"] as? String else { throw PhoneHarnessError.invalidArguments }
            let hasRegion = args.keys.contains { $0.hasPrefix("field_") }
            let region = PhoneTextRegion(arguments: args, frameWidth: imageSize.width, frameHeight: imageSize.height)
            if hasRegion && region == nil { throw PhoneHarnessError.invalidArguments }
            try device.agentType(text, replace: name == "replace_text")
            if text == "\n", name == "type" { return "Return submitted once; inspect fresh screen before another action." }
            let shot = try shrink(device.agentScreenshot(jpeg: false), width: imageSize.width)
            let lines = PhoneScreenReader.read(shot.data, width: shot.size.width, height: shot.size.height)
            let evidence = PhoneTextEvidence.status(expected: text, lines: lines, region: region)
            let receipt: [String: Any] = ["clipboard_verified": true, "paste_submitted": true,
                "field_evidence": evidence, "native_field_value_verified": false,
                "instruction": "OCR is supporting evidence only. Check the fresh screen for exact field contents and paste prompts. Do not blindly repeat this paste."]
            return String(data: try JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys]), encoding: .utf8)!
        case "press_key":
            guard let key = args["key"] as? String else { throw PhoneHarnessError.invalidArguments }
            try device.agentEditKey(key)
        case "home":
            try device.agentHome()
        case "open_app":
            try device.agentLaunch(bundleID: args["bundle_id"] as? String ?? "")
        default:
            throw NSError(domain: "agent", code: 1, userInfo: [NSLocalizedDescriptionKey: "unknown tool \(name)"])
        }
        // ponytail: 1519 is the undocumented "peek" tap; UIFeedbackGenerator is silent while
        // another app is in front, which is where the agent acts.
        AudioServicesPlaySystemSound(1519)
        return "ok"
    }

    nonisolated private static func compact(_ args: [String: Any]) -> String {
        args.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
    }

    /// Downscale so the model sees a small image; returns JPEG + its pixel size.
    nonisolated private static func shrink(_ data: Data, width: CGFloat = imageWidth) throws -> (data: Data, size: CGSize) {
        guard let image = UIImage(data: data) else {
            throw NSError(domain: "agent", code: 2, userInfo: [NSLocalizedDescriptionKey: "undecodable screenshot"])
        }
        let w = width
        let size = CGSize(width: w, height: (image.size.height * w / image.size.width).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return (small.jpegData(compressionQuality: 0.7) ?? data, size)
    }

    nonisolated static func verifyModel(config: LLMConfig) async throws -> String {
        try await ModelGateway.verifyModel(config: config)
    }
}
