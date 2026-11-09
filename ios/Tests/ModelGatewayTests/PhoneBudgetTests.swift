import XCTest
@testable import RoubaoModel

final class PhoneBudgetTests: XCTestCase {
    func testBudgetWaitRequiresOwnerDecisionThenAddsFiftyWithoutReplay() async throws {
        var (config, session, folder, calls, callbacks) = PhoneHarnessTests().setup()
        config.maxSteps = 1
        defer { try? FileManager.default.removeItem(at: folder) }
        let waiting = expectation(description: "waiting for budget decision")
        callbacks.budgetExceeded = { limit in
            XCTAssertEqual(limit, 1)
            waiting.fulfill()
            try await Task.sleep(for: .milliseconds(150))
            return .extend
        }
        MockTransport.scripted = [PhoneHarnessTests().response("tap", args: #"{"x":10,"y":20}"#), PhoneHarnessTests().response("tap", args: #"{"x":30,"y":40}"#), PhoneHarnessTests().response("done", args: #"{"summary":"continued"}"#)]
        let task = Task { try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session) }
        await fulfillment(of: [waiting], timeout: 2)
        let atLimit = await calls.counts(); XCTAssertEqual(atLimit.1, 1)
        XCTAssertEqual(MockTransport.requests.count, 1, "No model or phone work before the owner answers")
        let result = try await task.value; XCTAssertEqual(result, "done: continued")
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 2, "First click must not replay after extension")
        let saved = try PhoneHarness.savedTask(at: folder)
        let state = PhoneHarnessState(taskDirectory: folder.appendingPathComponent(saved.folder))
        let allowance = await state.allowance(default: 999)
        XCTAssertEqual(allowance, 51)
    }
    func testPausePreservesCheckpointAndContinueResumesCompletedWork() async throws {
        var (config, session, folder, calls, callbacks) = PhoneHarnessTests().setup()
        config.maxSteps = 1
        defer { try? FileManager.default.removeItem(at: folder) }
        callbacks.budgetExceeded = { _ in .pause }
        MockTransport.scripted = [PhoneHarnessTests().response("tap", args: #"{"x":10,"y":20}"#)]
        let paused = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        XCTAssertTrue(paused.hasPrefix("paused:"))
        let saved = try PhoneHarness.savedTask(at: folder)
        XCTAssertNotNil(saved.checkpoint)
        XCTAssertTrue(FileManager.default.fileExists(atPath: folder.appendingPathComponent(saved.folder).appendingPathComponent("task.json").path))
        callbacks.budgetExceeded = { _ in .extend }
        MockTransport.scripted = [PhoneHarnessTests().response("done", args: #"{"summary":"kept previous click"}"#)]
        let result = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, resume: true, session: session, resumeFolder: saved.folder)
        XCTAssertEqual(result, "done: kept previous click")
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 1)
    }
    func testBudgetDoesNotTreatAmbiguousOrNegativeReplyAsConsent() throws {
        XCTAssertEqual(PhoneStepBudget.decision("暂不继续"), .pause)
        XCTAssertEqual(PhoneStepBudget.decision("还没想好"), .unclear)
        XCTAssertEqual(PhoneStepBudget.decision("停止"), .stop)
        let payload = #"{"answers":[{"id":"step_budget","answer":"继续，再增加50步"}]}"#
        XCTAssertEqual(PhoneStepBudget.decision(payload), .extend)
    }
}
