import XCTest
import AgentRunKit
@testable import RoubaoModel

final class PhoneRecoveryTests: XCTestCase {
    func testTemporaryObservationFailureRecoversWithoutReplayingClick() async throws {
        let (config, session, folder, calls, original) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        var callbacks = original
        callbacks.capture = {
            let frame = await calls.capture()
            if await calls.counts().0 == 2 { throw URLError(.networkConnectionLost) }
            return frame
        }
        MockTransport.scripted = [PhoneHarnessTests().response("tap", args: #"{"x":10,"y":20}"#), PhoneHarnessTests().response("done", args: #"{"summary":"reobserved"}"#)]
        let result = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        XCTAssertEqual(result, "done: reobserved")
        let counts = await calls.counts(); XCTAssertEqual(counts.0, 3); XCTAssertEqual(counts.1, 1)
    }
    func testMainModelAlwaysHasRegisteredPhysicalToolsWithJevEnabled() async throws {
        let (config, session, folder, calls, callbacks, _) = JevActorTests().setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        MockTransport.scripted = [PhoneHarnessTests().response("tap", args: #"{"x":10,"y":20}"#), PhoneHarnessTests().response("done", args: #"{"summary":"main owns execution"}"#)]
        let result = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        XCTAssertEqual(result, "done: main owns execution")
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 1)
    }
    func testJevCannotMatchTextReturnsToMainWithoutAutomaticImageFallback() async throws {
        let (config, session, folder, calls, original, receipt) = JevActorTests().setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        var callbacks = original
        callbacks.capture = { .init(data: Data([0xFF, 0xD8]), width: 640, height: 1391, text: [.init(id: "t1", text: "abc", x: 20, y: 30, width: 50, height: 20, confidence: 1)]) }
        let args = String(decoding: try JSONSerialization.data(withJSONObject: ["steps_json": #"[{"action":"tap","target":"unavailable target"}]"#]), as: UTF8.self)
        MockTransport.scripted = [PhoneHarnessTests().response("set_plan", args: args),
            Data(#"{"answers":{"target":{"type":"choice","choice":"none","confidence":1,"probabilities":{"none":1,"t1":0}}}}"#.utf8),
            PhoneHarnessTests().response("done", args: #"{"summary":"main received failure"}"#)]
        _ = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session)
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 0)
        let saved = await receipt.get(); XCTAssertEqual(saved?.jevCalls, 1); XCTAssertEqual(saved?.mainCalls, 2)
    }
    func testThreePlatformProgressSurvivesAndSinglePlatformCannotFinish() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let state = PhoneHarnessState(taskDirectory: folder, prompt: "对比三个平台，生成 PDF")
        _ = try await state.updateProgress(#"[{"id":"jd","title":"京东","status":"pending"},{"id":"tb","title":"淘宝","status":"pending"},{"id":"pdd","title":"拼多多","status":"pending"}]"#)
        _ = try await state.updateProgress(#"[{"id":"jd","title":"京东","status":"complete","details":"Observed one offer"}]"#)
        let restored = PhoneHarnessState(resuming: true, taskDirectory: folder, prompt: "对比三个平台，生成 PDF")
        let pending = await restored.completionFeedback()
        XCTAssertTrue(pending?.contains("淘宝") == true); XCTAssertTrue(pending?.contains("拼多多") == true); XCTAssertTrue(pending?.contains("PDF") == true)
        let tools = PhoneHarness.tools(state: restored, callbacks: PhoneHarnessTests().setup().4)
        let done = try XCTUnwrap(tools.first { $0.name == "done" })
        let result = try await done.execute(arguments: Data(#"{"summary":"incorrectly finished"}"#.utf8), context: EmptyContext())
        XCTAssertTrue(result.isError)
    }
}
