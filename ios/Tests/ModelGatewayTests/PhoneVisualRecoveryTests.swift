import XCTest
@testable import RoubaoModel

final class PhoneVisualRecoveryTests: XCTestCase {
    func testTwoNoProgressClicksRequireImageGroundingAndResetAfterScreenChange() async {
        let state = PhoneHarnessState(); let screen = PhoneStallTests().frame()
        _ = await state.observed(screen)
        for i in 1...2 {
            await state.attempted("tap_text", arguments: Data(#"{"id":"t1"}"#.utf8)); _ = await state.observed(screen)
            let required = await state.requiresMultimodal(); XCTAssertEqual(required, i == 2)
        }
        let context = await state.context(); XCTAssertTrue(context.contains("MULTIMODAL_RECOVERY_REQUIRED"))
        _ = await state.observed(PhoneStallTests().frame("不同的文件选择页面"))
        let recovered = await state.requiresMultimodal(); XCTAssertFalse(recovered)
    }
    func testTwoUncertainLocatorsRequireImageGroundingWithoutAnyPhoneAction() async {
        let state = PhoneHarnessState()
        await state.locatorFailed(); let first = await state.requiresMultimodal(); XCTAssertFalse(first)
        await state.locatorFailed(); let second = await state.requiresMultimodal(); XCTAssertTrue(second)
        let metrics = await state.metrics(); XCTAssertEqual(metrics.actions, 0)
    }
    func testGroundedTargetIsSingleUseAndRejectsChangedScreen() async throws {
        let state = PhoneHarnessState(); let screen = PhoneStallTests().frame()
        _ = await state.observed(screen)
        let target = PhoneVisualTarget(target: "文件夹图标", left: 0.3, top: 0.7, right: 0.4, bottom: 0.8, confidence: 0.99, evidenceID: nil)
        try await state.storeVisualTarget(target, frame: screen)
        let first = try await state.consumeVisualTarget(target.id); XCTAssertEqual(first.x, 0.35, accuracy: 0.001)
        do { _ = try await state.consumeVisualTarget(target.id); XCTFail("Must not replay target") } catch {}
        try await state.storeVisualTarget(target, frame: screen)
        _ = await state.observed(PhoneStallTests().frame("changed page"))
        do { _ = try await state.consumeVisualTarget(target.id); XCTFail("Must reject stale target") } catch {}
    }
    func testHarnessForcesPrimaryVisualGroundingBeforeAnotherClick() async throws {
        var (config, session, root, calls, callbacks) = PhoneHarnessTests().setup(); config.maxSteps = 10
        defer { try? FileManager.default.removeItem(at: root) }
        callbacks.capture = { PhoneStallTests().frame() }
        let target = PhoneVisualTarget(target: "文件夹图标", left: 0.3, top: 0.7, right: 0.4, bottom: 0.8, confidence: 0.99, evidenceID: nil)
        callbacks.execute = { name, _, _ in
            if name == "locate_visual_target" { return String(decoding: try JSONEncoder().encode(target), as: UTF8.self) }
            await calls.execute(); return "ok"
        }
        let response = PhoneHarnessTests()
        MockTransport.scripted = [response.response("tap_text", args: #"{"id":"t1"}"#),response.response("tap_text", args: #"{"id":"t1"}"#),
            response.response("tap", args: #"{"x":240,"y":1188}"#), // blocked
            response.response("locate_visual_target", args: #"{"target":"文件夹图标"}"#),
            response.response("tap_visual_target", args: "{\"id\":\"\(target.id)\"}"),
            response.response("tap_visual_target", args: "{\"id\":\"\(target.id)\"}"), // consumed target blocked
            response.response("done", args: #"{"summary":"visual recovery verified"}"#)]
        let result = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        XCTAssertEqual(result, "done: visual recovery verified")
        let count = await calls.counts(); XCTAssertEqual(count.1, 3)
    }
    func testPrimaryGroundingActuallySendsImageWithoutOCRAndRejectsLowConfidence() async throws {
        let (config, session, root, _, _) = PhoneHarnessTests().setup(); defer { try? FileManager.default.removeItem(at: root) }
        let response = PhoneHarnessTests()
        let args = #"{"visible":true,"left":0.3,"top":0.7,"right":0.4,"bottom":0.8,"confidence":0.99,"reason":"icon"}"#
        MockTransport.scripted = [response.response("visual_target", args: args)]
        let frame = PhoneStallTests().frame("PRIVATE_OCR_SENTINEL")
        let result = try await ModelGateway.locateVisualTarget(target: "folder icon", frame: frame, config: config, session: session)
        XCTAssertEqual(result.y, 0.75, accuracy: 0.001)
        let body = try PhoneConversationTests().requestBody()
        XCTAssertTrue(body.contains("image_url")); XCTAssertFalse(body.contains("PRIVATE_OCR_SENTINEL"))
        let bad = try JSONSerialization.jsonObject(with: response.response("visual_target", args: args.replacingOccurrences(of: "0.99", with: "0.4"))) as! [String: Any]
        // response helper wraps the assistant; parse expects its message.
        let message = ((bad["choices"] as! [[String: Any]])[0]["message"] as! [String: Any])
        XCTAssertThrowsError(try PhoneVisualTarget.parse(message, target: "icon", evidenceID: nil))
    }
}
