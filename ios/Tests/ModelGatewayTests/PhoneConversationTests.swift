import XCTest
import AgentRunKit
@testable import RoubaoModel

final class PhoneConversationTests: XCTestCase {
    func requestBody() throws -> String {
        let request = try XCTUnwrap(MockTransport.requests.last)
        if let data = request.httpBody { return String(decoding: data, as: UTF8.self) }
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open(); defer { stream.close() }
        var data = Data(); var bytes = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&bytes, maxLength: bytes.count)
            guard count > 0 else { break }
            data.append(contentsOf: bytes.prefix(count))
        }
        return String(decoding: data, as: UTF8.self)
    }
    func testFollowUpAfterTerminalUsesPriorToolReceiptsAndSameFolderWithoutReplay() async throws {
        let (config, session, root, calls, callbacks) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: root) }
        let harness = PhoneHarnessTests()
        MockTransport.scripted = [harness.response("tap", args: #"{"x":10,"y":20}"#), harness.response("done", args: #"{"summary":"已生成 comparison.pdf，京东报价9699"}"#)]
        _ = try await PhoneHarness.run(prompt: "报价任务", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        let original = try PhoneHarness.savedTask(at: root)
        MockTransport.scripted = [harness.response("done", args: #"{"summary":"根据刚才结果继续回答"}"#)]
        let result = try await PhoneHarness.run(prompt: "继续解释刚才的报价", config: config, callbacks: callbacks, checkpointDirectory: root, session: session, resumeFolder: original.folder, followUp: true)
        XCTAssertEqual(result, "done: 根据刚才结果继续回答")
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 1)
        let updated = try PhoneHarness.savedTask(at: root)
        XCTAssertEqual(updated.folder, original.folder)
        XCTAssertEqual(updated.prompt, "继续解释刚才的报价")
        let body = try requestBody()
        XCTAssertTrue(body.contains("comparison.pdf"))
        XCTAssertTrue(body.contains("9699"))
        let payload = try JSONSerialization.jsonObject(with: Data(body.utf8)) as! [String: Any]
        for message in payload["messages"] as! [[String: Any]] {
            if let calls = message["tool_calls"] as? [Any] { XCTAssertFalse(calls.isEmpty, "Providers reject empty assistant tool_calls") }
        }
        XCTAssertTrue(body.contains("继续解释刚才的报价"))
    }
    func testLegacyConversationDoesNotInheritUnrelatedLatestTask() async throws {
        let (config, session, root, _, callbacks) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: root) }
        let harness = PhoneHarnessTests()
        MockTransport.scripted = [harness.response("done", args: #"{"summary":"OTHER_CHAT_SENTINEL"}"#)]
        _ = try await PhoneHarness.run(prompt: "other", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        let original = try PhoneHarness.savedTask(at: root)
        MockTransport.scripted = [harness.response("done", args: #"{"summary":"answer"}"#)]
        _ = try await PhoneHarness.run(prompt: "legacy followup", config: config, callbacks: callbacks, checkpointDirectory: root, session: session, followUp: true, fallbackHistory: "LEGACY_CHAT_SENTINEL")
        let body = try requestBody()
        XCTAssertTrue(body.contains("LEGACY_CHAT_SENTINEL")); XCTAssertFalse(body.contains("OTHER_CHAT_SENTINEL"))
        XCTAssertNotEqual(try PhoneHarness.savedTask(at: root).folder, original.folder)
    }
    func testReportEditKeepsOldFileButRequiresNewOutputAcrossRecovery() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let state = PhoneHarnessState(taskDirectory: directory, prompt: "修改 PDF")
        try await state.reportCreated("original.pdf")
        try await state.beginFollowUp(initialAllowance: 100)
        let feedbackBefore = await state.completionFeedback(); XCTAssertNotNil(feedbackBefore)
        let restored = PhoneHarnessState(resuming: true, taskDirectory: directory, prompt: "修改 PDF")
        let feedback = await restored.completionFeedback(); XCTAssertNotNil(feedback)
        let progress = try JSONDecoder().decode(PhoneTaskProgress.self, from: Data(contentsOf: directory.appendingPathComponent("progress.json")))
        XCTAssertEqual(progress.reportFile, "original.pdf")
        try await restored.reportCreated("revised.pdf")
        let completed = await restored.completionFeedback(); XCTAssertNil(completed)
    }

}
