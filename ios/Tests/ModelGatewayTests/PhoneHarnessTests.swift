import XCTest
import SwiftOpenAI
@testable import RoubaoModel
actor Calls {
    var captures = 0
    var executions = 0
    func capture() -> PhoneFrame { captures += 1; return .init(data: Data([1]), width: 450, height: 900) }
    func execute() { executions += 1 }
    func counts() -> (Int, Int) { (captures, executions) }
}
final class PhoneHarnessTests: XCTestCase {
    func response(_ name: String, args: String = "{}", extra: Bool = false) -> Data {
        let call: [String: Any] = ["id": UUID().uuidString, "type":"function", "function":["name":name,"arguments":args]]
        return try! JSONSerialization.data(withJSONObject:["choices":[["index":0,"message":["role":"assistant","content":"test","tool_calls":extra ? [call,call] : [call]],"finish_reason":"tool_calls"]]])
    }
    func setup() -> (LLMConfig, URLSession, URL, Calls, PhoneHarnessCallbacks) {
        MockTransport.requests = []; MockTransport.scripted = []
        let config = LLMConfig(chatURL: URL(string:"https://mock.invalid/v1/chat/completions")!, apiKey:"SECRET_TEST_SENTINEL", model:"mock", maxSteps:4, systemPrompt:"Test")
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [MockTransport.self]
        let session = URLSession(configuration:configuration)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let calls = Calls()
        let callbacks = PhoneHarnessCallbacks(capture:{ await calls.capture() }, execute:{ _,_,_ in await calls.execute(); return "ok" }, observed:{_ in}, approve:{_ in false})
        return (config,session,folder,calls,callbacks)
    }
    func testSerialToolsFreshScreensAndTerminalResume() async throws {
        let (config,session,folder,calls,callbacks) = setup(); defer { try? FileManager.default.removeItem(at:folder) }
        MockTransport.scripted = [response("tap",args:#"{"x":10,"y":20}"#),response("done",args:#"{"summary":"finished"}"#)]
        let result = try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,session:session)
        XCTAssertEqual(result,"done: finished")
        let counts = await calls.counts(); XCTAssertEqual(counts.0,2); XCTAssertEqual(counts.1,1)
        let requestCount=MockTransport.requests.count
        let resumed = try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,resume:true,session:session)
        XCTAssertEqual(resumed,result);XCTAssertEqual(MockTransport.requests.count,requestCount)
        for file in FileManager.default.enumerator(at:folder,includingPropertiesForKeys:nil)! {
            let url=file as! URL
            if url.pathExtension=="json" { XCTAssertFalse(try String(contentsOf:url).contains("SECRET_TEST_SENTINEL")) }
        }
    }
    func testDeniedApprovalNeverExecutesPhoneAction() async throws {
        let (config,session,folder,calls,callbacks)=setup();defer{try? FileManager.default.removeItem(at:folder)}
        MockTransport.scripted=[response("confirmed_action",args:#"{"description":"test","bundle_id":"example.app","action":"tap","arguments":{"x":1,"y":1}}"#),response("done",args:#"{"summary":"cancelled"}"#)]
        _ = try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,session:session)
        let counts=await calls.counts();XCTAssertEqual(counts.1,0)
    }
    func testMultipleActionsReturnToMainModelWithoutExecutingEither() async throws {
        let (config,session,folder,calls,callbacks)=setup();defer{try? FileManager.default.removeItem(at:folder)}
        MockTransport.scripted=[response("tap",extra:true), response("done",args:#"{"summary":"corrected without input"}"#)]
        let result = try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,session:session)
        XCTAssertEqual(result,"done: corrected without input")
        let counts=await calls.counts();XCTAssertEqual(counts.1,0)
    }
    func testCancelledTaskNeverCapturesOrExecutes() async throws {
        let (config,session,folder,calls,callbacks)=setup();defer{try? FileManager.default.removeItem(at:folder)}
        let task=Task { try Task.checkCancellation();return try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,session:session) }
        task.cancel()
        do { _=try await task.value;XCTFail("Expected cancellation") } catch {}
        let counts=await calls.counts();XCTAssertEqual(counts.1,0)
    }
}

actor RetryTransport: SwiftOpenAI.HTTPClient {
    var requests = 0
    func data(for request: SwiftOpenAI.HTTPRequest) async throws -> (Data, SwiftOpenAI.HTTPResponse) {
        requests += 1
        return (Data(), .init(statusCode: requests == 1 ? 503 : 200, headers: ["Retry-After":"0"]))
    }
    func bytes(for request: SwiftOpenAI.HTTPRequest) async throws -> (SwiftOpenAI.HTTPByteStream, SwiftOpenAI.HTTPResponse) { throw URLError(.unsupportedURL) }
    func count() -> Int { requests }
}
extension PhoneHarnessTests {
    func testTransientModelFailureRetriesWithoutPhoneAction() async throws {
        let transport = RetryTransport()
        let client = ProviderHTTPClient(base: transport)
        let result = try await client.data(for: .init(url: URL(string:"https://mock.invalid/chat/completions")!, method:.post, headers:[:]))
        XCTAssertEqual(result.1.statusCode,200)
        let count=await transport.count();XCTAssertEqual(count,2)
    }
    func testCancellationDuringObservationStopsBeforeAction() async throws {
        let (config,session,folder,calls,original)=setup();defer{try? FileManager.default.removeItem(at:folder)}
        var callbacks=original
        callbacks.capture={ try await Task.sleep(for:.seconds(30));return await calls.capture() }
        let task=Task { try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,session:session) }
        try await Task.sleep(for:.milliseconds(30));task.cancel()
        do { _=try await task.value;XCTFail("Expected cancellation") } catch {}
        let counts=await calls.counts();XCTAssertEqual(counts.1,0)
    }
}
extension PhoneHarnessTests {
    func testInterruptedRunReobservesWithoutReplayingCompletedTap() async throws {
        let (config,session,folder,calls,original)=setup();defer{try? FileManager.default.removeItem(at:folder)}
        var callbacks=original
        callbacks.capture={
            let frame=await calls.capture();let counts=await calls.counts()
            if counts.0==2 { throw URLError(.cannotDecodeContentData) }
            return frame
        }
        MockTransport.scripted=[response("tap",args:#"{"x":10,"y":20}"#),response("done",args:#"{"summary":"recovered"}"#)]
        do { _=try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,session:session);XCTFail("Expected interruption") } catch {}
        let result=try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,resume:true,session:session)
        XCTAssertEqual(result,"done: recovered")
        let counts=await calls.counts();XCTAssertEqual(counts.0,3);XCTAssertEqual(counts.1,1)
    }
}
extension PhoneHarnessTests {
    func largeUsageResponse(_ name: String, args: String = "{}") throws -> Data {
        var payload=try JSONSerialization.jsonObject(with:response(name,args:args)) as! [String:Any]
        payload["usage"]=["prompt_tokens":400_000,"completion_tokens":10,"total_tokens":400_010]
        return try JSONSerialization.data(withJSONObject:payload)
    }
    func testTaskContinuesBeyondFormerTokenBudget() async throws {
        let (config,session,folder,calls,callbacks)=setup();defer{try? FileManager.default.removeItem(at:folder)}
        MockTransport.scripted=[try largeUsageResponse("tap",args:#"{"x":10,"y":20}"#),try largeUsageResponse("done",args:#"{"summary":"completed beyond old limit"}"#)]
        let result=try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,session:session)
        XCTAssertEqual(result,"done: completed beyond old limit")
        let counts=await calls.counts();XCTAssertEqual(counts.0,2);XCTAssertEqual(counts.1,1)
    }
    func testResumeContinuesBeyondFormerTokenBudget() async throws {
        let (config,session,folder,calls,original)=setup();defer{try? FileManager.default.removeItem(at:folder)}
        var callbacks=original
        callbacks.capture={
            let frame=await calls.capture();let counts=await calls.counts()
            if counts.0==2 { throw URLError(.cannotDecodeContentData) }
            return frame
        }
        MockTransport.scripted=[try largeUsageResponse("tap",args:#"{"x":10,"y":20}"#),try largeUsageResponse("done",args:#"{"summary":"resumed beyond old limit"}"#)]
        do { _=try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,session:session);XCTFail("Expected interruption") } catch {}
        let result=try await PhoneHarness.run(prompt:"test",config:config,callbacks:callbacks,checkpointDirectory:folder,resume:true,session:session)
        XCTAssertEqual(result,"done: resumed beyond old limit")
        let counts=await calls.counts();XCTAssertEqual(counts.0,3);XCTAssertEqual(counts.1,1)
    }
}
