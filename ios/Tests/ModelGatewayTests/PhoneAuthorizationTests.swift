import XCTest
@testable import RoubaoModel

actor AuthorizationProbe {
    var approvals = 0, executions = 0
    func approve() -> Bool { approvals += 1; return true }
    func execute() throws -> String { executions += 1; if executions == 1 { throw PhoneHarnessError.staleScreen }; return "submitted" }
    func values() -> (Int, Int) { (approvals, executions) }
}
final class PhoneAuthorizationTests: XCTestCase {
    func request(description: String = "提交测试页的指定内容", app: String = "example.test", action: String = "tap", x: Int = 10, text: String? = nil, reference: String? = nil) throws -> String {
        var args: [String: Any] = ["x": x, "y": 20]
        if let text { args["text"] = text }
        var result: [String: Any] = ["description": description, "bundle_id": app, "action": action, "arguments": args]
        if let reference { result["authorization_id"] = reference }
        return String(decoding: try JSONSerialization.data(withJSONObject: result), as: UTF8.self)
    }
    func testStructuredCardConfirmationIsNotMistakenForCancellation() {
        for value in ["确认", "确认执行", "确认发送"] {
            XCTAssertEqual(PhoneApprovalDecision.parse(value), .approve)
            XCTAssertEqual(PhoneApprovalDecision.parse("{\"answers\":[{\"id\":\"decision\",\"answer\":\"\(value)\"}]}"), .approve)
        }
        for value in ["取消", "不确认", "暂不发送"] { XCTAssertEqual(PhoneApprovalDecision.parse("{\"answers\":[{\"answer\":\"\(value)\"}]}"), .deny) }
        for value in ["", "不确认也不要取消", "{\"approved\":true}", "{\"answers\":[{\"answer\":\"确认\"},{\"answer\":\"取消\"}]}"] { XCTAssertEqual(PhoneApprovalDecision.parse(value), .unclear) }
    }
    func testSameOperationRetainsAuthorizationAcrossCoordinateCorrection() throws {
        let original = try PhoneAuthorization.Request(request())
        let grant = original.approved(true)
        let relocated = try PhoneAuthorization.Request(request(x: 300, reference: grant.id))
        XCTAssertEqual(PhoneAuthorization.check(relocated, grants: [grant]), .reuse(grant.id))
    }
    func testAppDescriptionOrTypedContentChangeCannotReuseAuthorization() throws {
        let grant = try PhoneAuthorization.Request(request(action: "type", text: "指定内容")).approved(true)
        for changed in [try request(description: "不同的操作", action: "type", text: "指定内容", reference: grant.id), try request(app: "another.app", action: "type", text: "指定内容", reference: grant.id), try request(action: "type", text: "改变的内容", reference: grant.id)] {
            guard case .deny = PhoneAuthorization.check(try .init(changed), grants: [grant]) else { return XCTFail("Authorization scope must remain exact") }
        }
    }
    func testSubmittedUnknownAndDeniedOperationsDoNotReplayOrAskAgain() throws {
        let raw = try request(); let request = try PhoneAuthorization.Request(raw)
        for status in ["submitted", "dispatched", "unknown", "denied"] {
            var grant = request.approved(true); grant.status = status
            guard case .deny = PhoneAuthorization.check(request, grants: [grant]) else { return XCTFail("Must not replay \(status)") }
        }
    }
    func testAuthorizationSurvivesRestartAndUnknownDispatchBlocksReplay() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let raw = try request()
        let first = PhoneHarnessState(taskDirectory: dir)
        try await first.recordAuthorization(raw, allowed: true)
        let restored = PhoneHarnessState(resuming: true, taskDirectory: dir)
        let allowed = try await restored.checkAuthorization(raw)
        guard case .reuse = allowed else { return XCTFail("Approved operation lost") }
        _ = try await restored.dispatchAuthorization()
        let afterCrash = PhoneHarnessState(resuming: true, taskDirectory: dir)
        let uncertain = try await afterCrash.checkAuthorization(raw)
        guard case .deny = uncertain else { return XCTFail("Uncertain physical operation must not replay") }
    }
    func testHarnessAsksOnceThenReusesApprovalForVerifiedNotExecutedRetry() async throws {
        var (config, session, dir, _, callbacks) = PhoneHarnessTests().setup(); config.maxSteps = 6
        defer { try? FileManager.default.removeItem(at: dir) }
        let probe = AuthorizationProbe()
        callbacks.approve = { _ in await probe.approve() }
        callbacks.execute = { _, _, _ in try await probe.execute() }
        let response = PhoneHarnessTests()
        MockTransport.scripted = [response.response("confirmed_action", args: try request()), response.response("confirmed_action", args: try request(x: 300)), response.response("confirmed_action", args: try request(x: 300)), response.response("done", args: #"{"summary":"approval handled generically"}"#)]
        _ = try await PhoneHarness.run(prompt: "test authorization", config: config, callbacks: callbacks, checkpointDirectory: dir, session: session)
        let counts = await probe.values(); XCTAssertEqual(counts.0, 1); XCTAssertEqual(counts.1, 2)
    }
}

extension PhoneAuthorizationTests {
    func testRewordingSubmittedOperationCannotTriggerAnotherConfirmation() throws {
        let raw = try request(); var grant = try PhoneAuthorization.Request(raw).approved(true); grant.status = "submitted"
        let reworded = try PhoneAuthorization.Request(request(description: "把同一操作说明换句话说"))
        guard case .deny = PhoneAuthorization.check(reworded, grants: [grant]) else { return XCTFail("Rewording must not create another owner prompt") }
    }
    func testOwnerClarificationIsReturnedToModelWithoutPhysicalAction() async throws {
        var (config, session, dir, calls, callbacks) = PhoneHarnessTests().setup(); config.maxSteps = 4
        defer { try? FileManager.default.removeItem(at: dir) }
        callbacks.approve = { _ in false }
        callbacks.approvalComment = { "不要重复，先检查第一条结果" }
        MockTransport.scripted = [PhoneHarnessTests().response("confirmed_action", args: try request()), PhoneHarnessTests().response("done", args: #"{"summary":"按用户补充先核对"}"#)]
        _ = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: dir, session: session)
        let body = try PhoneConversationTests().requestBody(); XCTAssertTrue(body.contains("不要重复，先检查第一条结果"))
        let count = await calls.counts(); XCTAssertEqual(count.1, 0)
    }
}
