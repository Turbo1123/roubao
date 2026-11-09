import XCTest
@testable import RoubaoModel

final class PhoneStallTests: XCTestCase {
    func frame(_ text: String = "文件") -> PhoneFrame {
        .init(data: Data([1,2,3]), width: 640, height: 1387, text: [.init(id: "t1", text: text, x: 180, y: 800, width: 60, height: 25, confidence: 0.99)])
    }
    func testFourUnchangedActionsSuspendPlanAndKeepPrimaryInControlUntilProgress() async throws {
        let state = PhoneHarnessState()
        _ = await state.observed(frame())
        try await state.installPlan(#"[{"action":"tap","target":"文件"}]"#)
        for index in 1...4 {
            await state.attempted("tap_text", arguments: Data(#"{"id":"t1"}"#.utf8))
            _ = await state.observed(frame())
            let control = await state.requiresPrimaryForStall()
            XCTAssertEqual(control, index == 4)
        }
        let plan = await state.currentPlan(); XCTAssertNil(plan)
        let feedback = await state.context(); XCTAssertTrue(feedback.contains("4 consecutive"))
        try await state.installPlan(#"[{"action":"tap","target":"文件"}]"#)
        _ = await state.observed(frame()) // planning alone is not progress
        let repeated = await state.currentPlan(); XCTAssertNil(repeated)
        _ = await state.observed(frame("文件选择器"))
        let recovered = await state.requiresPrimaryForStall(); XCTAssertFalse(recovered)
    }
    func testPlanningOrReportsAloneDoNotCountAsFailedPhysicalActions() {
        var monitor = PhoneStallMonitor(); _ = monitor.observe(frame())
        for _ in 0..<10 {
            monitor.record("set_plan", arguments: Data("{}".utf8)); XCTAssertFalse(monitor.observe(frame()))
            monitor.record("create_pdf_report", arguments: Data("{}".utf8)); XCTAssertFalse(monitor.observe(frame()))
        }
        XCTAssertEqual(monitor.unchangedActions, 0)
    }
    func testChangedOCRResetsCountAndStatusBarClockDoesNot() {
        var monitor = PhoneStallMonitor()
        var screen = frame(); screen.text.append(.init(id: "clock", text: "7:40", x: 0, y: 10, width: 40, height: 20, confidence: 1))
        _ = monitor.observe(screen)
        for index in 0..<4 {
            screen.text[1] = .init(id: "clock", text: "7:4\(index)", x: 0, y: 10, width: 40, height: 20, confidence: 1)
            monitor.record("tap", arguments: Data("{}".utf8))
            XCTAssertEqual(monitor.observe(screen), index == 3)
        }
        _ = monitor.observe(frame("已打开文件选择器"))
        XCTAssertEqual(monitor.unchangedActions, 0)
    }
    func testActualHarnessBlocksBlindClicksAfterTwoUnchangedTaps() async throws {
        var (config, session, root, calls, original) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: root) }
        config.maxSteps = 8
        var callbacks = original
        callbacks.capture = { self.frame() }
        MockTransport.scripted = Array(repeating: PhoneHarnessTests().response("tap_text", args: #"{"id":"t1"}"#), count: 4)
            + [PhoneHarnessTests().response("done", args: #"{"summary":"main inspected stall"}"#)]
        let result = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        XCTAssertEqual(result, "done: main inspected stall")
        let body = try PhoneConversationTests().requestBody()
        XCTAssertTrue(body.contains("MULTIMODAL_RECOVERY_REQUIRED"))
        let count = await calls.counts(); XCTAssertEqual(count.1, 2)
    }
}
