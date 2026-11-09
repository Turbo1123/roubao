import XCTest
@testable import RoubaoModel
final class MockTransport: URLProtocol {
    static var requests: [URLRequest] = []
    static var scripted: [Data] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let data = Data(#"{"choices":[{"index":0,"message":{"role":"assistant","content":"完成","tool_calls":[{"id":"probe","type":"function","function":{"name":"done","arguments":"{\"summary\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}"#.utf8)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        let responseData = Self.scripted.isEmpty ? data : Self.scripted.removeFirst()
        client?.urlProtocol(self, didLoad: responseData)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
final class ModelGatewayTests: XCTestCase {
    func testCompatibleEndpointKeepsNestedPathAndToolHistory() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockTransport.self]
        let session = URLSession(configuration: configuration)
        for path in ["/api/v1", "/compatible-mode/v1", ""] {
            let config = LLMConfig(chatURL: URL(string:"https://mock.invalid\(path)/chat/completions")!, apiKey: "local-test-only", model: "vision-model", maxSteps: 5, systemPrompt: "")
            let reply = try await ModelGateway.complete(messages: [
                ["role":"system", "content":"test"],
                ["role":"user", "content":[["type":"image_url", "image_url":["url":"data:image/png;base64,AA=="]]]],
                ["role":"assistant", "content":"", "tool_calls":[["id":"previous", "type":"function", "function":["name":"tap", "arguments":"{}"]]]],
                ["role":"tool", "tool_call_id":"previous", "content":"ok"]
            ], config: config, session: session)
            let request = try XCTUnwrap(MockTransport.requests.last)
            XCTAssertEqual(request.url?.path, path + "/chat/completions")
            XCTAssertEqual(request.value(forHTTPHeaderField:"Authorization"), "Bearer local-test-only")
            XCTAssertEqual((reply["tool_calls"] as? [[String:Any]])?.first?["id"] as? String, "probe")
            let stream = request.httpBodyStream!
            stream.open(); var body = Data(); var buffer = [UInt8](repeating:0,count:4096)
            while stream.hasBytesAvailable { let count=stream.read(&buffer,maxLength:buffer.count); if count<=0 { break };body.append(buffer,count:count) };stream.close()
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with:body) as? [String:Any])
            XCTAssertEqual(json["parallel_tool_calls"] as? Bool, false)
            XCTAssertEqual((json["messages"] as? [[String:Any]])?.last?["tool_call_id"] as? String, "previous")
        }
    }
}
