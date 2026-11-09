import XCTest
@testable import RoubaoModel

final class PhoneCompletionTests: XCTestCase {
    func testPrimaryRejectsEarlierPDFSuccessWhenLatestSendIsIncompleteAndContinues() async throws {
        var (config, session, root, calls, callbacks) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: root) }
        config.reviewsCompletion = true
        config.maxSteps = 5
        let responses = PhoneHarnessTests()
        MockTransport.scripted = [
            responses.response("done", args: #"{"summary":"PDF 已生成，微信尚未发送，请你手动操作"}"#),
            responses.response("completion_check", args: #"{"status":"incomplete","evidence_or_remaining_work":"用户要求发送文件，当前只有预览，没有聊天发送结果"}"#),
            responses.response("ask_user_question", args: #"{"question":"目前还未发送，是否继续使用系统分享面板？"}"#),
            responses.response("done", args: #"{"summary":"按用户选择暂停发送"}"#),
            responses.response("completion_check", args: #"{"status":"complete","evidence_or_remaining_work":"用户已明确要求暂停发送"}"#)
        ]
        callbacks.execute = { name, _, _ in
            XCTAssertEqual(name, "ask_user_question")
            return "先暂停，不要发送"
        }
        let result = try await PhoneHarness.run(prompt: "把报告发给微信联系人", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        XCTAssertEqual(result, "done: 按用户选择暂停发送")
        XCTAssertEqual(MockTransport.requests.count, 5)
        let count = await calls.counts(); XCTAssertEqual(count.1, 0)
    }
    func testMalformedOrIncompleteVerdictCannotApproveCompletion() {
        XCTAssertNotNil(PhoneCompletionReview.rejection([:]))
        let reply: [String: Any] = ["tool_calls": [["function": ["name": "completion_check", "arguments": #"{"status":"unknown","evidence_or_remaining_work":"unverified"}"#]]]]
        XCTAssertNotNil(PhoneCompletionReview.rejection(reply))
    }
}
