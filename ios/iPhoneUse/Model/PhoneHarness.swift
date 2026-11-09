import Foundation
import AgentRunKit
import CoreGraphics

struct PhoneFrame: Sendable {
    let data: Data; let width: Double; let height: Double
    var text: [PhoneScreenText] = []
    var evidenceID: String? = nil
}
struct PhoneModelStep: Sendable {
    let index: Int; let name: String; let arguments: Data; let thought: String; let frame: PhoneFrame
}
struct PhoneHarnessCallbacks: Sendable {
    var capture: @Sendable () async throws -> PhoneFrame
    var execute: @Sendable (String, Data, PhoneFrame) async throws -> String
    var observed: @Sendable (PhoneModelStep) async -> Void
    var approve: @Sendable (String) async -> Bool
    var measured: @Sendable (PhoneRunMetrics) async -> Void = { _ in }
    var jevResponse: @Sendable (Data) async -> Void = { _ in }
    var routed: @Sendable (String) async -> Void = { _ in }
    var budgetExceeded: @Sendable (Int) async throws -> PhoneBudgetDecision = { _ in .pause }
    var prepared: @Sendable (PhoneSavedTask) async -> Void = { _ in }
    var answerRestored: @Sendable () async -> Void = {}
    var taskFiles: @Sendable () async -> [PhoneTaskFile] = { [] }
    var approvalComment: @Sendable () async -> String? = { nil }
}
actor PhoneHarnessState {
    private var frame: PhoneFrame?
    private var step = 0
    private var plan: JevPlan?
    private var submitted: Set<Int> = []
    private var pending: Int?
    private var userAnswer: String?
    private var resumeHandoff: Bool
    private var progress = PhoneTaskProgress()
    private let progressURL: URL?
    private let authorizationsURL: URL?
    private var authorizations: [PhoneAuthorization] = []
    private var pendingAuthorization: String?
    private let requestPrompt: String
    private var completionRejection: String?
    private let needsChecklist: Bool
    private let needsReport: Bool
    private var recovery: String?
    private var stall = PhoneStallMonitor()
    private var stallEvent: String?
    private var locatorFailures = 0
    private var jevUnavailable: String?
    private var visualEvent = false
    private var visualTarget: (PhoneVisualTarget, PhoneFrame)?
    init(resuming: Bool = false, taskDirectory: URL? = nil, prompt: String = "") {
        requestPrompt = prompt
        resumeHandoff = resuming
        progressURL = taskDirectory?.appendingPathComponent("progress.json")
        authorizationsURL = taskDirectory?.appendingPathComponent("authorizations.json")
        if let authorizationsURL, let data = try? Data(contentsOf: authorizationsURL), let saved = try? JSONDecoder().decode([PhoneAuthorization].self, from: data) { authorizations = saved }
        needsChecklist = ["对比", "比较", "compare"].contains { prompt.localizedCaseInsensitiveContains($0) }
        needsReport = prompt.localizedCaseInsensitiveContains("pdf")
        if let progressURL, let data = try? Data(contentsOf: progressURL), let saved = try? JSONDecoder().decode(PhoneTaskProgress.self, from: data) { progress = saved }
    }
    private func saveProgress() throws {
        guard let progressURL else { return }
        try FileManager.default.createDirectory(at: progressURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(progress).write(to: progressURL, options: .atomic)
    }
    func updateProgress(_ json: String) throws -> String {
        var updated = progress; try updated.merge(json)
        progress = updated; try saveProgress()
        return String(decoding: try JSONEncoder().encode(progress), as: UTF8.self)
    }
    func reportCreated(_ file: String) throws { progress.reportFile = file; progress.reportPending = false; try saveProgress() }
    func beginFollowUp(initialAllowance: Int) throws {
        progress.stepAllowance = initialAllowance
        // Previous reports remain available, but a requested new PDF must be created.
        progress.reportPending = needsReport
        try saveProgress()
        recovery = "This is a NEW USER TURN in the SAME conversation. Prior findings and files remain available. Follow the latest request; do not repeat completed research unless requested. Older pending items are context, not an instruction to ignore the new request."
    }
    func allowance(default value: Int) -> Int { max(1, progress.stepAllowance ?? value) }
    func setAllowance(_ value: Int) throws { progress.stepAllowance = value; try saveProgress() }
    func completionFeedback() -> String? { progress.completionFeedback(needsChecklist: needsChecklist, needsReport: needsReport) }
    func context() -> String {
        let snapshot = (try? String(decoding: JSONEncoder().encode(progress), as: UTF8.self)) ?? "{}"
        return "Current user request (continue this request, including owner corrections; earlier completed goals are historical context):\n" + requestPrompt + "\nPersistent task progress (update_task_progress records model findings; pending items must not be silently dropped):\n" + snapshot
            + (needsChecklist && progress.items.isEmpty ? "\nFirst record ALL requested platforms as pending; use the owner's submitted choices, not invented preferences." : "")
            + (recovery.map { "\nExecution feedback: " + $0 } ?? "")
            + (stall.feedback.map { "\n" + $0 } ?? "")
            + (jevUnavailable.map { "\n" + $0 } ?? "")
            + "\nOWNER AUTHORIZATIONS (reuse approved ID with EXACT same description/app/action/content; coordinates may be freshly relocated; do not ask repeatedly, and do not replay submitted/unknown operations):\n"
            + ((try? String(decoding: JSONEncoder().encode(authorizations), as: UTF8.self)) ?? "[]")
            + (requiresMultimodal() ? "\nMULTIMODAL_RECOVERY_REQUIRED: Two locating attempts failed or produced no visible progress. Old OCR IDs/guessed coordinates and JEV plans are blocked on this screen. Use locate_visual_target to inspect the actual tappable ICON/control using the primary model's image-only grounding, then tap_visual_target with its one-use ID. Or change the route / ask the owner. Do not merely repeat tap/tap_text or infer success. If sending/publishing, confirmed_action must reference arguments.visual_target_id and still requires owner approval." : "")
    }
    private func saveAuthorizations() throws {
        guard let authorizationsURL else { return }
        try FileManager.default.createDirectory(at: authorizationsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(authorizations).write(to: authorizationsURL, options: .atomic)
    }
    func checkAuthorization(_ raw: String) throws -> PhoneAuthorization.Check {
        let request = try PhoneAuthorization.Request(raw)
        let result = PhoneAuthorization.check(request, grants: authorizations)
        if case .reuse(let id) = result { pendingAuthorization = id }
        return result
    }
    func recordAuthorization(_ raw: String, allowed: Bool) throws {
        let request = try PhoneAuthorization.Request(raw)
        let grant = request.approved(allowed)
        authorizations.append(grant)
        try saveAuthorizations()
        pendingAuthorization = allowed ? grant.id : nil
    }
    func dispatchAuthorization() throws -> String {
        guard let id = pendingAuthorization, let index = authorizations.firstIndex(where: { $0.id == id && $0.status == "approved" }) else { throw PhoneHarnessError.invalidArguments }
        authorizations[index].status = "dispatched"
        try saveAuthorizations()
        return id
    }
    func finishAuthorization(_ id: String, status: String) throws {
        guard let index = authorizations.firstIndex(where: { $0.id == id }) else { return }
        authorizations[index].status = status
        pendingAuthorization = nil
        try saveAuthorizations()
    }
    func setCompletionReview(_ reason: String?) { completionRejection = reason }
    func completionReviewFeedback() -> String? { completionRejection }
    func returnToModel(_ message: String) { invalidatePlan(); recovery = message }
    func takeResumeHandoff() -> Bool { defer { resumeHandoff = false }; return resumeHandoff }
    func evidenceReceipt() -> String {
        frame?.evidenceID.map { "\nObserved screen evidence_id: \($0) (before this action). Use only observed facts in reports." } ?? ""
    }
    private var counters = PhoneRunMetrics(mode: "model")
    private let started = ProcessInfo.processInfo.systemUptime
    func installPlan(_ json: String, completion: String? = nil, expectedText: String? = nil) throws {
        invalidatePlan()
        plan = try JevPlan.decode(json, completion: completion, expectedText: expectedText); submitted = []; pending = nil
        recovery = nil
    }
    func currentPlan() -> (JevPlan, Set<Int>)? { plan.map { ($0, submitted) } }
    func invalidatePlan() { plan = nil; pending = nil }
    func storeAnswer(_ answer: String) { userAnswer = answer }
    func takeAnswer() -> String? { defer { userAnswer = nil }; return userAnswer }
    func generatedJev(_ id: String) { pending = Int(id.split(separator: "_")[2]) }
    func executed(_ name: String) {
        if let pending { submitted.insert(pending) }
        pending = nil
        recovery = nil
        if !["ask_user_question", "locate_visual_target", "list_task_files", "read_task_file"].contains(name) { counters.actions += 1 }
    }
    func captured(seconds: Double) { counters.captureSeconds += seconds }
    func modelCalled(seconds: Double) { counters.mainCalls += 1; counters.mainSeconds += seconds }
    func jevCalled(seconds: Double, fallback: Bool, reason: String? = nil) {
        counters.mode = "jev-first"; counters.jevCalls += 1; counters.jevSeconds += seconds
        if fallback { counters.fallbacks += 1; counters.lastFallback = reason }
    }
    func metrics() -> PhoneRunMetrics {
        var result = counters
        result.totalSeconds = ProcessInfo.processInfo.systemUptime - started
        return result
    }
    func jevFailure(_ error: Error) {
        if case JevError.http(let status) = error, [401, 403, 404, 451].contains(status) {
            jevUnavailable = "JEV HTTP \(status): unavailable for this run. Use the PRIMARY model's locate_visual_target; do not retry JEV or infer a missing target from this service error."
            invalidatePlan()
        }
    }
    func unavailableJev() -> String? { jevUnavailable }
    func requiresMultimodal() -> Bool { stall.requiresMultimodal || locatorFailures >= 2 }
    func locatorFailed() {
        let previous = requiresMultimodal()
        locatorFailures += 1
        if !previous && requiresMultimodal() { visualEvent = true; counters.fallbacks += 1 }
        if requiresMultimodal() { invalidatePlan() }
    }
    func takeVisualEvent() -> Bool { defer { visualEvent = false }; return visualEvent }
    func storeVisualTarget(_ target: PhoneVisualTarget, frame: PhoneFrame) throws {
        guard target.valid else { throw JevError.uncertain }
        visualTarget = (target, frame)
    }
    func consumeVisualTarget(_ id: String) throws -> PhoneVisualTarget {
        guard let current = frame, let saved = visualTarget, saved.0.id == id,
              PhoneVisualFreshness.matches(region: CGRect(x: saved.0.left, y: saved.0.top, width: saved.0.right - saved.0.left, height: saved.0.bottom - saved.0.top), before: saved.1, current: current) else { throw PhoneHarnessError.staleScreen }
        visualTarget = nil
        return saved.0
    }
    func attempted(_ name: String, arguments: Data) { stall.record(name, arguments: arguments) }
    func observed(_ image: PhoneFrame) -> Int {
        let wasVisual = requiresMultimodal()
        if let frame {
            let old = PhoneStallMonitor.Snapshot(frame), new = PhoneStallMonitor.Snapshot(image)
            if !old.samePage(as: new) { locatorFailures = 0; visualTarget = nil }
        }
        if stall.observe(image) { stallEvent = stall.feedback; counters.fallbacks += 1 }
        if !wasVisual && requiresMultimodal() { visualEvent = true; counters.fallbacks += 1 }
        if stall.requiresPrimary || requiresMultimodal() { invalidatePlan() }
        frame = image; step += 1; return step
    }
    func takeStallEvent() -> String? { defer { stallEvent = nil }; return stallEvent }
    func requiresPrimaryForStall() -> Bool { stall.requiresPrimary }
    func currentFrame() throws -> PhoneFrame {
        guard let frame else { throw PhoneHarnessError.missingFrame }; return frame
    }
}
enum PhoneHarnessError: Error, LocalizedError {
    case missingFrame, invalidReply, invalidArguments, staleScreen, checkpointMismatch
    var errorDescription: String? {
        switch self {
        case .missingFrame: "缺少当前截图，已停止操作"
        case .invalidReply: "模型必须每次返回一个手机工具调用"
        case .invalidArguments: "手机工具参数无效"
        case .staleScreen: "审批后页面已变化，动作未执行，请重新核对"
        case .checkpointMismatch: "恢复时需使用原任务的模型和接口"
        }
    }
}
struct PhoneRuntimeTool: AnyTool {
    typealias Context = EmptyContext
    let name: String
    let description: String
    let parametersSchema: AgentRunKit.JSONSchema
    let state: PhoneHarnessState
    let callbacks: PhoneHarnessCallbacks
    var isConcurrencySafe: Bool { false }
    var toolTimeout: Duration? { .seconds(45) }
    func execute(arguments: Data, context: EmptyContext) async throws -> ToolResult {
        try Task.checkCancellation()
        guard let object = try JSONSerialization.jsonObject(with: arguments) as? [String: Any] else { throw PhoneHarnessError.invalidArguments }
        if name == "done" {
            if let feedback = await state.completionFeedback() { return .error("完整任务尚未完成，继续执行或在肉包询问用户：\n" + feedback) }
            if let reason = await state.completionReviewFeedback() { return .error(reason) }
            return .success(object["summary"] as? String ?? "完成")
        }
        if name == "update_task_progress" {
            guard let json = object["items_json"] as? String else { throw PhoneHarnessError.invalidArguments }
            return .success(try await state.updateProgress(json))
        }
        if name == "ask_user_question" {
            guard let answer = await state.takeAnswer() else { throw PhoneHarnessError.invalidArguments }
            return .success(answer + (await state.evidenceReceipt()))
        }
        if await state.requiresMultimodal(), ["tap", "tap_text", "tap_target", "long_press", "set_plan"].contains(name) {
            return .error("连续两次定位未见进展，旧文字框和猜测坐标已禁用。请先 locate_visual_target 看图定位，再用 tap_visual_target；也可改路线或询问用户。未执行本次点击。")
        }
        if ["set_plan", "tap_target"].contains(name), let reason = await state.unavailableJev() { return .error(reason) }
        if name == "set_plan" {
            guard let json = object["steps_json"] as? String else { throw PhoneHarnessError.invalidArguments }
            try await state.installPlan(json, completion: object["completion_criterion"] as? String, expectedText: object["expected_text"] as? String)
            return .success("Plan installed. JEV will choose subsequent steps using fresh screenshots. If control returns to the planner, replace this plan based on the current screen; never replay already performed actions." + (await state.evidenceReceipt()))
        }
        let frame = try await state.currentFrame()
        var executionName = name
        var executionArguments = arguments
        if name == "tap_visual_target" {
            guard let id = object["id"] as? String else { throw PhoneHarnessError.invalidArguments }
            do {
                let target = try await state.consumeVisualTarget(id)
                executionName = "tap"
                executionArguments = try JSONSerialization.data(withJSONObject: ["x": target.x * frame.width, "y": target.y * frame.height, "grounding_source": "primary_multimodal", "_visual_region": ["x": target.left, "y": target.top, "width": target.right - target.left, "height": target.bottom - target.top]])
            } catch { return .error("视觉定位已过期或已使用，请根据当前截图重新 locate_visual_target；没有点击。") }
        }
        if name == "confirmed_action", object["action"] as? String == "tap" {
            var updated = object
            var inner = object["arguments"] as? [String: Any] ?? [:]
            if let id = inner["visual_target_id"] as? String {
                do {
                    let target = try await state.consumeVisualTarget(id)
                    inner["x"] = target.x * frame.width; inner["y"] = target.y * frame.height
                    inner["_visual_region"] = ["x": target.left, "y": target.top, "width": target.right - target.left, "height": target.bottom - target.top]
                    updated["arguments"] = inner
                    executionArguments = try JSONSerialization.data(withJSONObject: updated)
                } catch { return .error("审批后的视觉目标已过期；请重新观察和定位，未执行发送。") }
            } else if await state.requiresMultimodal() { return .error("当前需先多模态定位，并在 arguments.visual_target_id 引用目标；未执行已审批动作。") }
        }
        if name != "confirmed_action", let args = try? JSONSerialization.jsonObject(with: executionArguments) as? [String: Any],
           let label = PhoneFinalActionGuard.blockedLabel(name: executionName, arguments: args, frame: frame) {
            await state.returnToModel("Unapproved direct activation of final-action control \(label) was BLOCKED. Use confirmed_action with a matching approved authorization. If an earlier operation is submitted/unknown, VERIFY its result instead of repeating it or requesting another confirmation. No action was submitted by this blocked tool.")
            return .error("普通点击不能触发“" + label + "”等最终动作；本次没有执行。请使用合法授权；已有提交记录时先核对，禁止重发。")
        }
        await state.attempted(name, arguments: executionArguments)
        let executionStarted = ProcessInfo.processInfo.systemUptime
        var authorizationID: String?
        do {
            if name == "confirmed_action" { authorizationID = try await state.dispatchAuthorization() }
            let result = try await callbacks.execute(executionName, executionArguments, frame)
            if name == "locate_visual_target" { await state.modelCalled(seconds: ProcessInfo.processInfo.systemUptime - executionStarted) }
            if name == "locate_visual_target" {
                guard let data = result.data(using: .utf8), let target = try? JSONDecoder().decode(PhoneVisualTarget.self, from: data), target.valid else {
                    await state.locatorFailed()
                    return .error("图片定位未得到可靠目标，没有点击。主模型应换目标、改路线或询问用户。")
                }
                try await state.storeVisualTarget(target, frame: frame)
            }
            if name == "tap_target", let receipt = try? JSONSerialization.jsonObject(with: Data(result.utf8)) as? [String: Any], receipt["execution_status"] != nil || receipt["executed"] as? Bool == false { await state.locatorFailed() }
            await state.executed(name)
            if name == "create_pdf_report", let receipt = try? JSONSerialization.jsonObject(with: Data(result.utf8)) as? [String: Any],
               receipt["pdf_created"] as? Bool == true, let file = receipt["file"] as? String { try await state.reportCreated(file) }
            if ["type", "replace_text"].contains(name), let receipt = try? JSONSerialization.jsonObject(with: Data(result.utf8)) as? [String: Any],
               let evidence = receipt["field_evidence"] as? String, evidence != "ocr_region_matches" {
                await state.returnToModel("Input was submitted once but field contents are not independently verified (\(evidence)). Inspect the fresh screen/paste prompt before any next action. Do NOT replay the paste blindly.")
            }
            if let authorizationID {
                try await state.finishAuthorization(authorizationID, status: "submitted")
                let receipt: [String: Any] = ["authorization_id": authorizationID, "owner_approved": true, "delivery": "action_submitted_verify_screen", "result": result, "instruction": "Do not request the same confirmation or repeat this action. Inspect the actual result first."]
                return .success(String(decoding: try JSONSerialization.data(withJSONObject: receipt), as: UTF8.self) + (await state.evidenceReceipt()))
            }
            return .success(result + (await state.evidenceReceipt()))
        } catch is CancellationError {
            if let authorizationID { try? await state.finishAuthorization(authorizationID, status: "unknown") }
            throw CancellationError()
        }
        catch {
            if let authorizationID {
                let definitelyNotDispatched = (error as? PhoneHarnessError).map { if case .staleScreen = $0 { return true }; if case .invalidArguments = $0 { return true }; return false } ?? ((error as NSError).domain == "owner-control")
                try? await state.finishAuthorization(authorizationID, status: definitelyNotDispatched ? "approved" : "unknown")
            }
            if ["tap_target", "locate_visual_target"].contains(name) { await state.locatorFailed() }
            await state.returnToModel("Tool \(name) failed: \(error.localizedDescription). Delivery may be unknown. Reobserve first; only the main model chooses recovery. Never replay the failed action automatically.")
            return .error("工具未确认成功：\(error.localizedDescription)。请根据新截图决定后续动作，禁止盲目重放。")
        }
    }
}
struct PhoneHarnessClient: ToolCallSurfacingClient {
    let config: LLMConfig
    var session: URLSession? = nil
    let state: PhoneHarnessState
    let callbacks: PhoneHarnessCallbacks
    var providerIdentifier: ProviderIdentifier { .openAICompatible }
    func generate(messages: [ChatMessage], tools: [ToolDefinition], responseFormat: ResponseFormat?, requestContext: RequestContext?) async throws -> AssistantMessage {
        try Task.checkCancellation()
        let captureStarted = ProcessInfo.processInfo.systemUptime
        let frame = try await callbacks.capture()
        await state.captured(seconds: ProcessInfo.processInfo.systemUptime - captureStarted)
        let step = await state.observed(frame)
        var allowsSingleStepHandoff = await state.takeResumeHandoff()
        if let feedback = await state.takeStallEvent() {
            await callbacks.routed("连续4次操作未见进展，已暂停旧计划并交回主模型核对")
            await state.returnToModel(feedback)
        }
        if await state.takeVisualEvent() {
            await callbacks.routed("连续2次定位未见进展，已切换主模型多模态重新定位；旧编号和坐标已禁用")
        }
        let stalled = await state.requiresPrimaryForStall()
        let visualRecovery = await state.requiresMultimodal()
        if stalled || visualRecovery { allowsSingleStepHandoff = true }
        var wire: [[String: Any]] = try messages.map { message in
            switch message {
            case .system(let text): return ["role": "system", "content": text]
            case .user(let text): return ["role": "user", "content": text]
            case .userMultimodal(let parts): return ["role": "user", "content": try parts.map { try JSONSerialization.jsonObject(with: JSONEncoder().encode($0)) }]
            case .assistant(let value):
                return ["role": "assistant", "content": value.content,
                    "tool_calls": value.toolCalls.map { ["id": $0.id, "type": "function", "function": ["name": $0.name, "arguments": $0.arguments]] }]
            case .tool(let id, _, let text): return ["role": "tool", "tool_call_id": id, "content": text]
            }
        }
        wire.append(["role": "user", "content": await state.context()])
        let files = await callbacks.taskFiles()
        let manifest = String(decoding: try JSONEncoder().encode(files), as: UTF8.self)
        wire.append(["role": "user", "content": "FILES PRODUCED BY THIS CONVERSATION (stable IDs, actual names, types, sizes and page counts):\n" + manifest + "\nUse read_task_file to inspect content and share_task_file to put the exact file into the native iOS share sheet. Do not hunt through unrelated WeChat Recent Files. Opening the share sheet is not sending. Never tap Roubao's Stop Task button; it belongs to the human owner."])

        if let (plan, submitted) = await state.currentPlan(), submitted.count == plan.steps.count,
           plan.steps.allSatisfy({ $0.action == "open_app" }) {
            await state.invalidatePlan()
            wire.append(["role": "user", "content": "The planned app launch completed. Plan the REMAINING goal from this screen. If the user requested clearing/recomputing, an old matching result does not satisfy that procedure."])
        }
        if let jev = config.jev, let (plan, submitted) = await state.currentPlan() {
            let started = ProcessInfo.processInfo.systemUptime
            // A known bundle launch is an OS operation, not a visual target.
            // Never ask a grounding model to find an icon before this API call.
            if let next = plan.steps.indices.first(where: { !submitted.contains($0) }), plan.steps[next].action == "open_app" {
                let arguments = try JSONSerialization.data(withJSONObject: ["bundle_id": plan.steps[next].bundle_id ?? ""])
                let id = "jev_\(plan.id)_\(next)_\(UUID().uuidString)"
                await state.generatedJev(id)
                await callbacks.observed(.init(index: step, name: "open_app", arguments: arguments, thought: "计划 · 通过系统接口启动应用", frame: frame))
                return AssistantMessage(content: "Planned OS launch", toolCalls: [.init(id: id, name: "open_app", arguments: String(decoding: arguments, as: UTF8.self))])
            }
            do {
                let history = wire.suffix(12).compactMap { item -> String? in
                    guard let role = item["role"] as? String else { return nil }
                    return role + ": " + String(describing: item["tool_calls"] ?? item["content"] ?? "")
                }.joined(separator: "\n")
                let goal = messages.compactMap { message -> String? in if case .user(let text) = message { return text }; return nil }.last ?? ""
                let request = try JevActor.request(goal: goal, plan: plan, submitted: submitted, history: history, frame: frame, config: jev)
                let data = try await TypeSafeGateway.send(request, session: session)
                await callbacks.jevResponse(data)
                let decision = try JevActor.parse(data, plan: plan, submitted: submitted, frame: frame)
                try Task.checkCancellation()
                await state.jevCalled(seconds: ProcessInfo.processInfo.systemUptime - started, fallback: false)
                if decision.name == "done" {
                    // A plan may cover only a subgoal, such as opening a place card.
                    // Only the planner can finish the original user request.
                    await state.invalidatePlan()
                    await callbacks.routed("JEV 已核对本段计划，交回主模型核对完整任务")
                    wire.append(["role": "user", "content": "JEV verified this plan's completion criterion: \(plan.completion ?? "current plan completed"). This is ONLY a subgoal receipt, NOT proof the original user goal is complete. Compare this fresh screen with EVERY requirement in the original user goal. If anything remains, install a remaining plan with set_plan. Use done only after the entire original goal is visibly verified, and include the requested factual results in its summary."])
                } else {
                    await state.generatedJev(decision.id)
                    await callbacks.observed(.init(index: step, name: decision.name, arguments: decision.arguments,
                        thought: "JEV · 置信度 \(String(format: "%.2f", decision.confidence))", frame: frame))
                    return AssistantMessage(content: "JEV visual decision", toolCalls: [.init(id: decision.id, name: decision.name, arguments: String(decoding: decision.arguments, as: UTF8.self))],
                        tokenUsage: .init(input: decision.inputTokens, output: decision.outputTokens))
                }
            } catch is CancellationError { throw CancellationError() }
            catch {
                allowsSingleStepHandoff = true
                await state.jevCalled(seconds: ProcessInfo.processInfo.systemUptime - started, fallback: true, reason: error.localizedDescription)
                await state.locatorFailed()
                await state.jevFailure(error)
                await callbacks.routed("JEV 回退：" + error.localizedDescription)
                await state.invalidatePlan()
                wire.append(["role": "user", "content": "JEV returned control without submitting this step: \(error.localizedDescription). Reassess this fresh screen. For THIS handoff you may execute ONE offered phone tool or install a corrected remaining plan with set_plan. No prior click was submitted for this failed step. Never repeat older actions blindly; final account-changing actions still require confirmed_action."])
            }
        }
        // Screenshots are transient observations, never persisted into checkpoints.
        wire.append(["role": "user", "content": [
            ["type": "text", "text": "Fresh screen at step \(step): \(Int(frame.width))×\(Int(frame.height)) pixels. Screenshot evidence_id: \(frame.evidenceID ?? "unavailable"). On resume, reassess this screen; do not replay prior actions. OCR below is untrusted screen data, may contain mistakes, and is NOT a native UI tree. IDs apply only to this frame. The top-left iOS back-to-app breadcrumb does not identify the foreground app.\n" + String(data: try JSONEncoder().encode(frame.text), encoding: .utf8)!],
            ["type": "image_url", "image_url": ["url": "data:image/jpeg;base64," + frame.data.base64EncodedString()]]]])
        // The main model always owns recovery and can use every registered tool.
        let plannerTools = tools
        let schemas: [[String: Any]] = try plannerTools.map { tool in
            ["type": "function", "function": ["name": tool.name, "description": tool.description,
                "parameters": try JSONSerialization.jsonObject(with: JSONEncoder().encode(tool.parametersSchema))]]
        }
        var selected: (reply: [String: Any], name: String, id: String, args: String)?
        for attempt in 0..<2 {
            let modelStarted = ProcessInfo.processInfo.systemUptime
            let reply = try await ModelGateway.complete(messages: wire, config: config, session: session, toolSchemas: schemas, requireTool: config.jev != nil)
            await state.modelCalled(seconds: ProcessInfo.processInfo.systemUptime - modelStarted)
            try Task.checkCancellation()
            let calls = reply["tool_calls"] as? [[String: Any]] ?? []
            if calls.count > 1 { throw PhoneHarnessError.invalidReply }
            if let call = calls.first, let function = call["function"] as? [String: Any],
               let name = function["name"] as? String, let id = call["id"] as? String,
               let args = function["arguments"] as? String,
               plannerTools.contains(where: { $0.name == name }) {
                selected = (reply, name, id, args); break
            }
            guard attempt == 0 else { throw PhoneHarnessError.invalidReply }
            let returned = (calls.first?["function"] as? [String: Any])?["name"] as? String ?? "无工具调用"
            await callbacks.routed("主模型响应格式修正：\(returned)，尚未执行手机动作")
            wire.append(["role": "user", "content": "Your previous response was not executed: it did not contain exactly one offered tool call. Returned tool: \(returned). Return ONE tool from this exact offered list: \(plannerTools.map(\.name).joined(separator: ", ")). For UI actions use set_plan when physical tools are not offered. Do not merely narrate an action. No phone action occurred, so do not invent a result."])
        }
        guard let selected else { throw PhoneHarnessError.invalidReply }
        let (reply, name, id, args) = selected
        if name == "done", config.reviewsCompletion, await state.completionFeedback() == nil {
            var review = wire
            review.append(["role": "user", "content": "Before ending, independently check whether the LATEST request and any submitted owner correction are satisfied. Proposed summary: " + args + ". Call completion_check. If the requested send/edit/navigation is unfinished, status must be incomplete; the same primary model will then continue or ask the owner."])
            let started = ProcessInfo.processInfo.systemUptime
            do {
                let verdict = try await ModelGateway.complete(messages: review, config: config, session: session, toolSchemas: PhoneCompletionReview.tools, requireTool: true)
                await state.modelCalled(seconds: ProcessInfo.processInfo.systemUptime - started)
                let reason = PhoneCompletionReview.rejection(verdict)
                await state.setCompletionReview(reason)
                if reason != nil { await callbacks.routed("主模型完成核对发现目标尚未达成，保持原任务继续") }
            } catch is CancellationError { throw CancellationError() }
            catch { await state.setCompletionReview("尚未完成最终目标核对，请继续核对或询问用户：" + error.localizedDescription) }
        }
        let thought = (allowsSingleStepHandoff ? "主模型单步接管 · " : "") + (reply["content"] as? String ?? "")
        await callbacks.observed(.init(index: step, name: name, arguments: Data(args.utf8), thought: thought, frame: frame))
        let usage = reply["_usage"] as? [String: Int]
        return AssistantMessage(content: thought, toolCalls: [.init(id: id, name: name, arguments: args)],
            tokenUsage: usage.map { TokenUsage(input: max(0, $0["input"] ?? 0), output: max(0, $0["output"] ?? 0)) })
    }
    func stream(messages: [ChatMessage], tools: [ToolDefinition], requestContext: RequestContext?) -> AsyncThrowingStream<StreamDelta, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let result = try await generate(messages: messages, tools: tools, responseFormat: nil, requestContext: requestContext)
                    continuation.yield(.content(result.content))
                    for (index, call) in result.toolCalls.enumerated() {
                        continuation.yield(.toolCallStart(index: index, id: call.id, name: call.name, kind: .function))
                        continuation.yield(.toolCallDelta(index: index, arguments: call.arguments))
                    }
                    continuation.yield(.finished(usage: result.tokenUsage))
                    continuation.yield(.streamClosed(terminalMarkerSeen: true))
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

enum PhoneHarness {
    static func tools(state: PhoneHarnessState, callbacks: PhoneHarnessCallbacks, allowPlan: Bool = false, allowLocator: Bool = true) -> [PhoneRuntimeTool] {
        var list: [PhoneRuntimeTool] = ModelGateway.tools.compactMap { entry in
            guard let f = entry["function"] as? [String: Any], let name = f["name"] as? String,
                  let parameters = f["parameters"] as? [String: Any], let props = parameters["properties"] as? [String: [String: String]],
                  allowLocator || name != "tap_target" else { return nil }
            let schemas = props.mapValues { value -> AgentRunKit.JSONSchema in
                value["type"] == "string" ? .string(description: value["description"]) : .number(description: value["description"])
            }
            return PhoneRuntimeTool(name: name, description: f["description"] as? String ?? "", parametersSchema: .object(properties: schemas, required: parameters["required"] as? [String] ?? []), state: state, callbacks: callbacks)
        }
        if allowPlan {
            list.append(PhoneRuntimeTool(name: "set_plan", description: "Install a complete remaining UI plan for the fast JEV actor. steps_json is a JSON array, 1-20 entries. Every entry: action (tap/type/replace_text/open_app/press_key/swipe_up/swipe_down), target (precise visible label or purpose), optional text (literal input or return/backspace/escape), optional bundle_id (open_app). One tap per entry; separate calculator digit taps. completion_criterion: ONE specific final visible outcome, in clear English, not historical procedure; e.g. The Calculator displays final result 391. expected_text: optional exact visible result text for independent local OCR check, e.g. 391. Do not include done. Account-changing/final write actions require confirmed_action, never this plan.", parametersSchema: .object(properties: ["steps_json": .string(), "completion_criterion": .string(), "expected_text": .string()], required: ["steps_json", "completion_criterion"]), state: state, callbacks: callbacks))
        }
        list.append(PhoneRuntimeTool(name: "confirmed_action", description: "Execute a final publishing, sending, replying or account-changing action only after owner approval. Provide exact target and content in description, current app bundle_id, action, and arguments.", parametersSchema: .object(properties: ["description": .string(), "bundle_id": .string(), "action": .string(enumValues: ["tap", "type", "replace_text", "press_key", "long_press"]), "authorization_id": .string(description: "Reuse an approved authorization ID with the exact original description, app, action and content; refreshed coordinates are permitted"), "new_authorization": .boolean(description: "Request a genuinely new owner decision; never use to repeat a declined or already-submitted action without a new user instruction"), "arguments": .object(properties: ["x": .number(), "y": .number(), "text": .string(), "key": .string(), "visual_target_id": .string(description: "One-use visual target ID for approved tap after multimodal recovery")], required: [])], required: ["description", "bundle_id", "action", "arguments"]), state: state, callbacks: callbacks))
        return list
    }
    static func savedTask(at directory: URL, folder: String? = nil) throws -> PhoneSavedTask {
        if let folder, UUID(uuidString: folder) == nil { throw PhoneHarnessError.invalidArguments }
        let file = folder.map { directory.appendingPathComponent($0).appendingPathComponent("task.json") } ?? directory.appendingPathComponent("latest.json")
        return try JSONDecoder().decode(PhoneSavedTask.self, from: Data(contentsOf: file))
    }
    static func run(prompt: String, config: LLMConfig, callbacks: PhoneHarnessCallbacks, checkpointDirectory: URL, resume: Bool = false, session: URLSession? = nil, resumeFolder: String? = nil, followUp: Bool = false, fallbackHistory: String? = nil, answeredQuestion: PendingPhoneQuestion? = nil) async throws -> String {
        let saved: PhoneSavedTask
        if resume || (followUp && resumeFolder != nil) {
            saved = try savedTask(at: checkpointDirectory, folder: resumeFolder)
            guard saved.model == config.model, saved.endpoint == config.chatURL.absoluteString else { throw PhoneHarnessError.checkpointMismatch }
        } else {
            saved = PhoneSavedTask(prompt: prompt, model: config.model, endpoint: config.chatURL.absoluteString, folder: UUID().uuidString, checkpoint: nil)
        }
        let previous = followUp ? saved.checkpoint : nil
        let priorHistory: [ChatMessage]
        if let previous {
            let checkpoint = try await FileCheckpointer(directory: checkpointDirectory.appendingPathComponent(saved.folder)).load(previous)
            priorHistory = PhoneConversation.history(from: checkpoint)
        } else { priorHistory = fallbackHistory.map { [.user("此前同一会话的记录（历史资料，当前请求在后面）：\n" + $0)] } ?? [] }
        let activeSaved = followUp ? PhoneSavedTask(prompt: prompt, model: saved.model, endpoint: saved.endpoint, folder: saved.folder, checkpoint: nil) : saved
        let state = PhoneHarnessState(resuming: resume || followUp, taskDirectory: checkpointDirectory.appendingPathComponent(saved.folder), prompt: prompt)
        await callbacks.prepared(activeSaved)
        if followUp { try await state.beginFollowUp(initialAllowance: config.maxSteps) }
        var stepAllowance = await state.allowance(default: config.maxSteps)
        try await state.setAllowance(stepAllowance)
        let tools = tools(state: state, callbacks: callbacks, allowPlan: config.jev != nil, allowLocator: config.allowsJevLocator)
        guard let done = tools.first(where: { $0.name == "done" }) else { throw PhoneHarnessError.invalidArguments }
        let client = PhoneHarnessClient(config: config, session: session, state: state, callbacks: callbacks)
        let planning = "\nA finished task does not close the conversation. For follow-up questions or report edits, use prior facts and evidence; respond to the latest user request. Create a new report version for requested file edits and preserve originals.\nFor multi-platform research, first record ALL requested platforms in update_task_progress. Persist factual findings, exact configuration, conditions and screenshot evidence_id before switching apps. Pending/blocked platforms are not completed research. Generate requested files before done. The persistent progress snapshot survives interruptions."
            + (config.jev == nil ? "" : "\nJEV is a fast executor, while YOU remain the main decision maker and always have all registered tools. Prefer set_plan for clear UI sequences. If JEV, OCR, input verification or any tool fails, inspect the new screenshot and decide whether to use a direct tool, change the plan, or ask the owner. Do not end the whole task on a recoverable step failure. Never put account-changing final actions in a plan. Never replay uncertain input/actions blindly.")
        let systemPrompt = config.systemPrompt + planning + "\nAsk the owner directly via ask_user_question when a preference is needed; use questions_json to show 1-3 concise questions with helpful choices and allow free text. Never answer your own question or infer consent from waiting. Before a final publishing/sending/replying/account change, use confirmed_action with exact description and target. Never perform such a final action via a plain tap/type/replace_text/press_key/tap_text/tap_target or a JEV plan. After any user question, reopen the target app and reobserve. All literal text uses clipboard paste, independent of keyboard layout. Input receipts distinguish clipboard verification from field OCR evidence. Never automatically repeat a paste after failure or uncertain evidence; inspect the next screen. Use replace_text for a focused field's entire content and press_key for Return. A top-left back-to-app breadcrumb is not the foreground app identity."
        let checkpointer = PhoneCheckpointStore(root: checkpointDirectory, task: activeSaved)
        let approval: ToolApprovalHandler = { request in
            if request.toolName == "ask_user_question" {
                // AgentRunKit's approval wait is cancellation-aware and has no
                // action timeout. Execute only the already-submitted answer later.
                do {
                    let frame = try await state.currentFrame()
                    let answer = try await callbacks.execute("ask_user_question", Data(request.arguments.utf8), frame)
                    try Task.checkCancellation()
                    await state.storeAnswer(answer)
                    return .approve
                } catch is CancellationError { return .deny(reason: "任务已停止，没有提交用户答复") }
                catch { return .deny(reason: "询问未展示，请修正参数或重新询问：\(error.localizedDescription)") }
            }
            do {
                switch try await state.checkAuthorization(request.arguments) {
                case .reuse: return .approve
                case .deny(let reason): return .deny(reason: reason)
                case .askOwner:
                    let allowed = await callbacks.approve(request.arguments)
                    try Task.checkCancellation()
                    try await state.recordAuthorization(request.arguments, allowed: allowed)
                    let comment = await callbacks.approvalComment()
                    return allowed ? .approve : .deny(reason: comment.map { "用户没有授权原动作并补充了答复，请在同一任务里调整：" + $0 } ?? "用户明确取消")
                }
            } catch is CancellationError { return .deny(reason: "任务已停止，未执行操作") }
            catch { return .deny(reason: "授权状态未保存，未执行操作：" + error.localizedDescription) }
        }
        if !resume {
            // Save the new user turn before calling the network. Even a first-request
            // failure must resume THIS turn rather than an older terminal result.
            let seed = AgentCheckpoint(messages: [.system(systemPrompt)] + priorHistory + [.user(prompt)],
                iteration: 0, tokenUsage: TokenUsageTotals(), sessionID: SessionID(), runID: RunID())
            try await checkpointer.save(seed)
        }
        var checkpoint = resume ? saved.checkpoint : nil
        if let answeredQuestion, let text = answeredQuestion.continuationText, let previous = saved.checkpoint {
            guard resume, answeredQuestion.question.taskFolder == nil || answeredQuestion.question.taskFolder == saved.folder else { throw PhoneHarnessError.checkpointMismatch }
            if answeredQuestion.question.kind == "action_approval", let raw = answeredQuestion.question.approvalArguments, let answer = answeredQuestion.answer {
                switch try await state.checkAuthorization(raw) {
                case .askOwner:
                    let decision = PhoneApprovalDecision.parse(answer)
                    if decision != .unclear { try await state.recordAuthorization(raw, allowed: decision == .approve) }
                case .reuse, .deny: break
                }
            }
            let previousState = try await checkpointer.load(previous)
            let continued = AgentCheckpoint(messages: [.system(systemPrompt)] + PhoneConversation.history(from: previousState) + [.user(text)],
                iteration: previousState.iteration, tokenUsage: previousState.tokenUsage, sessionID: previousState.sessionID, runID: RunID())
            try await checkpointer.save(continued)
            checkpoint = continued.checkpointID
            await callbacks.answerRestored()
            await state.returnToModel("The owner has already answered your pending question. Their correction is in the conversation; inspect the fresh screen and continue the current task without restarting or repeating uncertain actions.")
        }
        var recoveries = 0
        runLoop: while true {
            do {
                let agent = Agent(client: client, tools: tools.filter { $0.name != "done" }, completionTool: done,
                    configuration: .init(maxIterations: stepAllowance, toolTimeout: .seconds(45), systemPrompt: systemPrompt, maxMessages: 80, approvalPolicy: .tools(["confirmed_action", "ask_user_question"])))
                let stream: AsyncThrowingStream<StreamEvent, Error>
                if let checkpoint {
                    stream = try await agent.resume(from: checkpoint, checkpointer: checkpointer, context: EmptyContext(), tokenBudget: nil, approvalHandler: approval)
                } else {
                    stream = agent.stream(userMessage: prompt, history: priorHistory, context: EmptyContext(), tokenBudget: nil, approvalHandler: approval, checkpointer: checkpointer)
                }
                for try await event in stream {
                    try Task.checkCancellation()
                    if case let .finished(_, content, reason, _) = event.kind {
                        await callbacks.measured(state.metrics())
                        if reason == .completed { return "done: " + (content ?? "完成") }
                        if case .maxIterationsReached(let limit) = reason {
                            await callbacks.routed("已执行 \(limit) 步，进度已保存，等待用户决定是否增加50步")
                            let decision = try await callbacks.budgetExceeded(limit)
                            try Task.checkCancellation()
                            switch decision {
                            case .extend:
                                stepAllowance = limit + PhoneStepBudget.increment
                                try await state.setAllowance(stepAllowance)
                                checkpoint = try savedTask(at: checkpointDirectory, folder: saved.folder).checkpoint
                                await state.returnToModel("The owner explicitly approved 50 more steps. Continue the SAME task from the new screenshot and persistent findings; do not restart completed platforms or replay old actions.")
                                continue runLoop
                            case .pause, .unclear: return "paused: 步数已到 \(limit)，进度与截图已保留，可点击继续任务"
                            case .stop: return "stopped: 用户停止任务，进度与截图已保留"
                            }
                        }
                        return "stopped: " + (reason?.description ?? "任务结束")
                    }
                }
                throw PhoneHarnessError.invalidReply
            } catch {
                try Task.checkCancellation()
                guard recoveries < 2, recoverable(error) else { await callbacks.measured(state.metrics()); throw error }
                let latest = try? savedTask(at: checkpointDirectory)
                checkpoint = latest?.folder == saved.folder ? latest?.checkpoint : nil
                if checkpoint == nil, await state.metrics().actions > 0 { await callbacks.measured(state.metrics()); throw error }
                recoveries += 1
                await state.returnToModel("A recoverable model/transport failure interrupted the next decision: \(error.localizedDescription). The harness did NOT replay any phone action. Inspect the new screenshot, persistent progress and last tool receipt; YOU decide recovery.")
                await callbacks.routed("任务进度已保留，正在重新观察并交回主模型（\(recoveries)/2）：\(error.localizedDescription)")
                try await Task.sleep(for: .milliseconds(300))
            }
        }
    }
    private static func recoverable(_ error: Error) -> Bool {
        if let error = error as? PhoneHarnessError, case .invalidReply = error { return true }
        let error = error as NSError
        if error.domain == NSURLErrorDomain { return [-1001, -1003, -1004, -1005, -1009].contains(error.code) }
        return error.domain == "model" && error.code == 2
    }
}

struct PhoneSavedTask: Codable, Sendable {
    let prompt: String; let model: String; let endpoint: String; let folder: String
    var checkpoint: CheckpointID?
}
actor PhoneCheckpointStore: AgentCheckpointer {
    private let root: URL
    private var task: PhoneSavedTask
    private let file: FileCheckpointer
    init(root: URL, task: PhoneSavedTask) {
        self.root = root; self.task = task
        file = FileCheckpointer(directory: root.appendingPathComponent(task.folder))
    }
    func save(_ checkpoint: AgentCheckpoint) async throws {
        try await file.save(checkpoint)
        task.checkpoint = checkpoint.checkpointID
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var directory = root; try directory.setResourceValues(values)
        try JSONEncoder().encode(task).write(to: root.appendingPathComponent("latest.json"), options: .atomic)
        try JSONEncoder().encode(task).write(to: root.appendingPathComponent(task.folder).appendingPathComponent("task.json"), options: .atomic)
    }
    func load(_ id: CheckpointID) async throws -> AgentCheckpoint { try await file.load(id) }
    func list(session: SessionID) async throws -> [CheckpointID] { try await file.list(session: session) }
}
