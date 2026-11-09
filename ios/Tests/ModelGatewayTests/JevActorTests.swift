import XCTest
@testable import RoubaoModel

actor MetricsReceipt {
    var value: PhoneRunMetrics?
    func store(_ value: PhoneRunMetrics) { self.value = value }
    func get() -> PhoneRunMetrics? { value }
}

final class JevActorTests: XCTestCase {
    let planJSON = #"[{"action":"tap","target":"输入框"},{"action":"type","target":"在已聚焦输入框输入 hello","text":"hello"}]"#
    func answer(choice: String, ids: [String], complete: Double = 0) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["model": "jev-test", "answers": [
            "step": ["type": "choice", "choice": choice, "confidence": 1, "probabilities": Dictionary(uniqueKeysWithValues: ids.map { ($0, $0 == choice ? 1.0 : 0.0) })],
            "complete": ["type": "noul", "noul": complete],
            "ready": ["type": "noul", "noul": choice == "other" ? 0.0 : 0.99],
            "visible_0": ["type": "noul", "noul": 0.99],
            "box_0": ["type": "box", "confidence": 0.99, "box": ["left": 0.1, "right": 0.4, "top": 0.1, "bottom": 0.2]]],
            "usage": ["input_tokens": 100, "output_tokens": 10]])
    }
    func setup() -> (LLMConfig, URLSession, URL, Calls, PhoneHarnessCallbacks, MetricsReceipt) {
        var (config, session, folder, calls, callbacks) = PhoneHarnessTests().setup()
        config.jev = .init(apiKey: "JEV_TEST_ONLY")
        config.maxSteps = 8
        callbacks.capture = { .init(data: Data([0xFF, 0xD8, 0xFF, 0xD9]), width: 450, height: 978) }
        let receipt = MetricsReceipt()
        callbacks.measured = { await receipt.store($0) }
        return (config, session, folder, calls, callbacks, receipt)
    }
    func testJevExecutesWithoutPerStepMainCallsThenPlannerVerifiesWholeGoal() async throws {
        let (config, session, folder, calls, callbacks, receipt) = setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        let args = String(data: try JSONSerialization.data(withJSONObject: ["steps_json": planJSON]), encoding: .utf8)!
        MockTransport.scripted = [PhoneHarnessTests().response("set_plan", args: args),
            try answer(choice: "s0", ids: ["s0", "done", "other"]),
            try answer(choice: "s1", ids: ["s1", "done", "other"]),
            try answer(choice: "done", ids: ["done", "other"], complete: 0.99),
            PhoneHarnessTests().response("done", args: #"{"summary":"hello verified"}"#)]
        let result = try await PhoneHarness.run(prompt: "type hello", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        XCTAssertTrue(result.hasPrefix("done:"))
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 2)
        let saved = await receipt.get()
        let metrics = try XCTUnwrap(saved)
        XCTAssertEqual(metrics.mainCalls, 2); XCTAssertEqual(metrics.jevCalls, 3); XCTAssertEqual(metrics.fallbacks, 0)
        XCTAssertEqual(MockTransport.requests.filter { $0.url?.path.hasSuffix("systemone") == true }.count, 3)
    }

    func testIntermediatePlanCompletionContinuesOriginalGoal() async throws {
        var (config, session, folder, calls, callbacks, receipt) = setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        // Each executed subgoal changes the UI. Reusing an unchanged dummy
        // screenshot would now correctly trigger two-failure visual recovery.
        callbacks.capture = {
            let count = await calls.counts()
            return .init(data: Data([0xFF, 0xD8, 0xFF, 0xD9, UInt8(count.1)]), width: 450, height: 978)
        }
        func stage(_ target: String, _ criterion: String) throws -> Data {
            let steps = String(data: try JSONSerialization.data(withJSONObject: [["action": "tap", "target": target]]), encoding: .utf8)!
            let args = String(data: try JSONSerialization.data(withJSONObject: ["steps_json": steps, "completion_criterion": criterion]), encoding: .utf8)!
            return PhoneHarnessTests().response("set_plan", args: args)
        }
        MockTransport.scripted = [try stage("place card", "place detail visible"),
            try answer(choice: "s0", ids: ["s0"]), try answer(choice: "done", ids: ["done"], complete: 0.99),
            try stage("walking route", "walking distance and time visible"),
            try answer(choice: "s0", ids: ["s0"]), try answer(choice: "done", ids: ["done"], complete: 0.99),
            PhoneHarnessTests().response("done", args: #"{"summary":"route distance and time verified"}"#)]
        let result = try await PhoneHarness.run(prompt: "Find a place AND read walking distance and time", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        XCTAssertEqual(result, "done: route distance and time verified")
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 2)
        let metrics = await receipt.get(); XCTAssertEqual(metrics?.mainCalls, 3); XCTAssertEqual(metrics?.jevCalls, 4)
        let main = MockTransport.requests.filter { $0.url?.path.hasSuffix("chat/completions") == true }
        for request in main.dropFirst() {
            let stream = try XCTUnwrap(request.httpBodyStream)
            stream.open(); defer { stream.close() }
            var body = Data(), buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                body.append(buffer, count: count)
            }
            XCTAssertTrue(String(decoding: body, as: UTF8.self).contains("NOT proof the original user goal"))
        }
    }

    func testUnregisteredToolIsNeverExecutedEvenAcrossResponseRecovery() async throws {
        let (config, session, folder, calls, callbacks, _) = setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        MockTransport.scripted = Array(repeating: PhoneHarnessTests().response("never_registered_tool"), count: 6)
        do {
            _ = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
            XCTFail("An unoffered physical tool must be rejected")
        } catch {}
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 0)
    }
    func testOffPlanFallsBackWithoutPhoneAction() async throws {
        let (config, session, folder, calls, callbacks, receipt) = setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        let args = String(data: try JSONSerialization.data(withJSONObject: ["steps_json": planJSON]), encoding: .utf8)!
        MockTransport.scripted = [PhoneHarnessTests().response("set_plan", args: args), Data(#"{"answers":{"visible_0":{"type":"noul","noul":0.1},"box_0":{"type":"box","confidence":0.9,"box":{"left":0.1,"right":0.2,"top":0.1,"bottom":0.2}}}}"#.utf8),
            PhoneHarnessTests().response("done", args: #"{"summary":"stopped safely"}"#)]
        _ = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 0)
        let saved = await receipt.get()
        let metrics = try XCTUnwrap(saved)
        XCTAssertEqual(metrics.mainCalls, 2); XCTAssertEqual(metrics.jevCalls, 1); XCTAssertEqual(metrics.fallbacks, 1)
    }
    func testUncertainJevOffersOneObservedMainModelActionThenReturnsToPlanning() async throws {
        let (config, session, folder, calls, callbacks, receipt) = setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        let args = String(data: try JSONSerialization.data(withJSONObject: ["steps_json": planJSON]), encoding: .utf8)!
        MockTransport.scripted = [PhoneHarnessTests().response("set_plan", args: args),
            Data(#"{"answers":{"visible_0":{"type":"noul","noul":0.1},"box_0":{"type":"box","confidence":0.9,"box":{"left":0.1,"right":0.2,"top":0.1,"bottom":0.2}}}}"#.utf8),
            PhoneHarnessTests().response("tap", args: #"{"x":10,"y":20}"#),
            PhoneHarnessTests().response("done", args: #"{"summary":"goal verified"}"#)]
        let result = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        XCTAssertEqual(result, "done: goal verified")
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 1)
        let metrics = await receipt.get(); XCTAssertEqual(metrics?.mainCalls, 3); XCTAssertEqual(metrics?.fallbacks, 1)
    }
    func testResumeAllowsObservedSingleStepWithoutRestoringStalePlan() async throws {
        let (config, session, folder, calls, original, _) = setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        var interrupted = original
        interrupted.capture = {
            let frame = await calls.capture()
            if await calls.counts().0 == 2 { throw URLError(.cannotDecodeContentData) }
            return .init(data: Data([0xFF, 0xD8]), width: frame.width, height: frame.height)
        }
        let args = String(data: try JSONSerialization.data(withJSONObject: ["steps_json": planJSON]), encoding: .utf8)!
        MockTransport.scripted = [PhoneHarnessTests().response("set_plan", args: args)]
        do { _ = try await PhoneHarness.run(prompt: "test", config: config, callbacks: interrupted, checkpointDirectory: folder, session: session); XCTFail("Expected interruption") } catch {}
        MockTransport.scripted = [PhoneHarnessTests().response("open_app", args: #"{"bundle_id":"test.app"}"#), PhoneHarnessTests().response("done", args: #"{"summary":"continued"}"#)]
        let result = try await PhoneHarness.run(prompt: "test", config: config, callbacks: original, checkpointDirectory: folder, resume: true, session: session)
        XCTAssertEqual(result, "done: continued")
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 1)
    }
    func testSubmittedStepsAndUnsupportedActionsRejected() throws {
        let plan = try JevPlan.decode(planJSON)
        let frame = PhoneFrame(data: Data(), width: 450, height: 978)
        let next = try JevActor.parse(answer(choice: "s0", ids: ["s0", "s1", "done", "other"]), plan: plan, submitted: [0], frame: frame)
        XCTAssertEqual(next.name, "type")
        XCTAssertTrue(next.id.contains("_1_"))
        XCTAssertThrowsError(try JevActor.parse(answer(choice: "done", ids: ["done", "other"], complete: 0.2), plan: plan, submitted: [0, 1], frame: frame))
        XCTAssertThrowsError(try JevPlan.decode(#"[{"action":"confirmed_action","target":"send"}]"#))
    }
    func testPlannedOSLaunchDoesNotRequireVisibleAppIcon() async throws {
        let (config, session, folder, calls, callbacks, receipt) = setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        let plan = #"[{"action":"open_app","target":"计算器","bundle_id":"com.apple.calculator"}]"#
        let args = String(data: try JSONSerialization.data(withJSONObject: ["steps_json": plan]), encoding: .utf8)!
        MockTransport.scripted = [PhoneHarnessTests().response("set_plan", args: args),
            PhoneHarnessTests().response("done", args: #"{"summary":"opened"}"#)]
        _ = try await PhoneHarness.run(prompt: "open calculator", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 1)
        let saved = await receipt.get(), metrics = try XCTUnwrap(saved)
        XCTAssertEqual(metrics.mainCalls, 2); XCTAssertEqual(metrics.jevCalls, 0)
    }
    func testIndexedTargetUsesObservedIDAndSendsNoScreenshot() throws {
        let plan = try JevPlan.decode(#"[{"action":"tap","target":"数字键2"}]"#)
        let frame = PhoneFrame(data: Data([0xFF, 0xD8]), width: 640, height: 1391, text: [
            .init(id: "t1", text: "2", x: 200, y: 1000, width: 30, height: 40, confidence: 0.99),
            .init(id: "t2", text: "3", x: 350, y: 1000, width: 30, height: 40, confidence: 0.99)])
        let request = try JevActor.request(goal: "23×17", plan: plan, submitted: [], history: "", frame: frame, config: .init(apiKey: "JEV_TEST_ONLY"))
        let body = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
        XCTAssertNil(body["assets"])
        XCTAssertNil(request.value(forHTTPHeaderField: "TypeSafe-Danger-Zone-Images"))
        let answer: [String: Any] = ["answers": ["target": ["type": "choice", "choice": "t1", "confidence": 0.99, "probabilities": ["t1": 0.99, "t2": 0.01, "none": 0.0]]]]
        let decision = try JevActor.parse(JSONSerialization.data(withJSONObject: answer), plan: plan, submitted: [], frame: frame)
        XCTAssertEqual(decision.name, "tap_text")
        let arguments = try JSONSerialization.jsonObject(with: decision.arguments) as! [String: String]
        XCTAssertEqual(arguments, ["id": "t1"])
        var invalid = answer
        invalid["answers"] = ["target": ["type": "choice", "choice": "stale-id", "confidence": 1, "probabilities": ["t1": 0, "t2": 0, "none": 0, "stale-id": 1]]]
        XCTAssertThrowsError(try JevActor.parse(JSONSerialization.data(withJSONObject: invalid), plan: plan, submitted: [], frame: frame))
    }
}
