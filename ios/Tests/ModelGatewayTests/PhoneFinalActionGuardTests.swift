import XCTest
@testable import RoubaoModel
final class PhoneFinalActionGuardTests: XCTestCase {
    func frame(_ label: String) -> PhoneFrame {
        .init(data: Data([1,2]), width: 640, height: 1387, text: [.init(id: "commit", text: label, x: 390, y: 1190, width: 60, height: 25, confidence: 0.99), .init(id: "nav", text: "查看详情", x: 80, y: 100, width: 90, height: 25, confidence: 1)])
    }
    func testFinalLabelsAreGuardedAcrossAppsAndAllCoordinateOrOCRRoutes() {
        for label in ["发送", "Send", "发布", "Delete", "提交", "收藏"] {
            XCTAssertNotNil(PhoneFinalActionGuard.blockedLabel(name: "tap", arguments: ["x": 421, "y": 1205], frame: frame(label)))
            XCTAssertNotNil(PhoneFinalActionGuard.blockedLabel(name: "tap_text", arguments: ["id": "commit"], frame: frame(label)))
            XCTAssertNotNil(PhoneFinalActionGuard.blockedLabel(name: "long_press", arguments: ["x": 421, "y": 1205], frame: frame(label)))
            XCTAssertNil(PhoneFinalActionGuard.blockedLabel(name: "tap_text", arguments: ["id": "nav"], frame: frame(label)))
        }
    }
    func testChatReturnCannotBypassVisibleSendControl() {
        XCTAssertNotNil(PhoneFinalActionGuard.blockedLabel(name: "press_key", arguments: ["key": "return"], frame: frame("发送")))
        XCTAssertNotNil(PhoneFinalActionGuard.blockedLabel(name: "type", arguments: ["text": "\n"], frame: frame("发送")))
        XCTAssertNil(PhoneFinalActionGuard.blockedLabel(name: "type", arguments: ["text": "hello\nworld"], frame: frame("发送")))
    }
    func testHarnessBlocksPlainTapAfterSubmittedOperationEvenWhenModelTriesBypass() async throws {
        var (config, session, root, calls, callbacks) = PhoneHarnessTests().setup(); config.maxSteps = 5
        defer { try? FileManager.default.removeItem(at: root) }
        callbacks.capture = { self.frame("Send") }
        callbacks.approve = { _ in true }
        MockTransport.scripted = [PhoneHarnessTests().response("confirmed_action", args: #"{"description":"在测试页提交指定内容","bundle_id":"example.test","action":"tap","arguments":{"x":421,"y":1205}}"#),
            PhoneHarnessTests().response("tap", args: #"{"x":421,"y":1205}"#),
            PhoneHarnessTests().response("tap_text", args: #"{"id":"commit"}"#),
            PhoneHarnessTests().response("done", args: #"{"summary":"no duplicate submission"}"#)]
        _ = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        let count = await calls.counts(); XCTAssertEqual(count.1, 1)
    }
}
