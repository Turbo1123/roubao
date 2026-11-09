import XCTest
import CoreGraphics
@testable import RoubaoModel

final class PhoneFileControlTests: XCTestCase {
    let screen = PhoneFrame(data: Data(), width: 640, height: 1387, text: [
        .init(id: "t19", text: "停止任务", x: 270, y: 650, width: 100, height: 40, confidence: 0.99),
        .init(id: "t18", text: "查看已生成的PDF", x: 40, y: 580, width: 250, height: 40, confidence: 0.99)])
    let region = CGRect(x: 0.03, y: 0.46, width: 0.94, height: 0.07)
    func testModelCannotTapOwnerStopByCoordinatesOrOCRButCanOpenFile() {
        XCTAssertTrue(PhoneOwnerControls.blocks(name: "tap", arguments: ["x": 318.0, "y": 673.0], frame: screen, region: region))
        XCTAssertTrue(PhoneOwnerControls.blocks(name: "tap_text", arguments: ["id": "t19"], frame: screen, region: region))
        XCTAssertTrue(PhoneOwnerControls.blocks(name: "long_press", arguments: ["x": 318.0, "y": 673.0], frame: screen, region: nil))
        XCTAssertFalse(PhoneOwnerControls.blocks(name: "tap_text", arguments: ["id": "t18"], frame: screen, region: region))
        XCTAssertFalse(PhoneOwnerControls.blocks(name: "tap", arguments: ["x": 400.0, "y": 120.0], frame: screen, region: region))
    }
    func testStableFileIDsAndUnsafePaths() {
        let name = "原版报告.pdf"
        XCTAssertEqual(PhoneTaskFile.identifier(name), PhoneTaskFile.identifier(name))
        XCTAssertNotEqual(PhoneTaskFile.identifier(name), PhoneTaskFile.identifier("摘要版.pdf"))
        let file = PhoneTaskFile(id: PhoneTaskFile.identifier("../secret"), filename: "../secret", title: "test", mediaType: "text/plain", bytes: 0, pages: nil, summary: nil)
        XCTAssertFalse(file.valid)
    }
    func testEveryPrimaryDecisionIncludesScopedTaskFileManifest() async throws {
        var (config, session, root, _, callbacks) = PhoneHarnessTests().setup(); config.maxSteps = 4
        defer { try? FileManager.default.removeItem(at: root) }
        let name = "原版11页比价报告.pdf"
        callbacks.taskFiles = { [.init(id: PhoneTaskFile.identifier(name), filename: name, title: "原版", mediaType: "application/pdf", bytes: 1234, pages: 11, summary: nil)] }
        MockTransport.scripted = [PhoneHarnessTests().response("done", args: #"{"summary":"files known"}"#)]
        _ = try await PhoneHarness.run(prompt: "发原版", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        let body = try PhoneConversationTests().requestBody()
        XCTAssertTrue(body.contains(name)); XCTAssertTrue(body.contains(PhoneTaskFile.identifier(name)))
        let payload = try JSONSerialization.jsonObject(with: Data(body.utf8)) as! [String: Any]
        let messages = payload["messages"] as! [[String: Any]]
        let text = try XCTUnwrap(messages.compactMap { $0["content"] as? String }.first { $0.contains("FILES PRODUCED BY THIS CONVERSATION") })
        let line = String(text.split(separator: "\n")[1])
        let manifest = try JSONDecoder().decode([PhoneTaskFile].self, from: Data(line.utf8))
        XCTAssertEqual(manifest.first?.mediaType, "application/pdf")
        XCTAssertEqual(manifest.first?.pages, 11)
        XCTAssertTrue(body.contains("share_task_file"))
    }
}
