import XCTest
@testable import RoubaoModel

final class TypeSafeTests: XCTestCase {
    func response(visible: Double = 0.98, confidence: Double = 0.96, left: Double = 0.2, right: Double = 0.4) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["answers": ["visible": ["type": "noul", "noul": visible], "box": ["type": "box", "confidence": confidence, "box": ["left": left, "right": right, "top": 0.2, "bottom": 0.4]]]])
    }
    func testAbsentOrUncertainTargetNeverProducesClickCoordinates() throws {
        XCTAssertThrowsError(try TypeSafeGateway.parse(response(visible: 0.01)))
        XCTAssertThrowsError(try TypeSafeGateway.parse(response(confidence: 0.3)))
        XCTAssertThrowsError(try TypeSafeGateway.parse(response(left: 0.6, right: 0.4)))
        XCTAssertThrowsError(try TypeSafeGateway.parse(response(left: -0.1)))
        var malformed = try JSONSerialization.jsonObject(with: response()) as! [String: Any]
        var answers = malformed["answers"] as! [String: Any]
        answers["visible"] = ["type": "noul", "noul": true]
        malformed["answers"] = answers
        XCTAssertThrowsError(try TypeSafeGateway.parse(JSONSerialization.data(withJSONObject: malformed)))
        let target = try TypeSafeGateway.parse(response())
        XCTAssertEqual(target.x, 0.3, accuracy: 0.001)
        XCTAssertEqual(target.y, 0.3, accuracy: 0.001)
    }
    func testImageRequestUsesDedicatedEndpointMultipartAndOptInHeader() throws {
        let image = Data([0xFF, 0xD8, 0xFF, 0xD9])
        let frame = PhoneFrame(data: image, width: 450, height: 980)
        let request = try TypeSafeGateway.request(target: "搜索输入框", frame: frame, config: .init(apiKey: "TEST_ONLY"))
        XCTAssertEqual(request.url?.absoluteString, "https://api.typesafe.ai/v1/systemone")
        XCTAssertEqual(request.value(forHTTPHeaderField: "TypeSafe-Danger-Zone-Images"), "true")
        let body = request.httpBody!
        let text = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(text.contains("name=\"request\""))
        XCTAssertTrue(text.contains("name=\"screen\""))
        XCTAssertTrue(text.contains("jev-latest"))
        XCTAssertFalse(text.contains("TEST_ONLY"))
        let imageRange = try XCTUnwrap(body.range(of: image))
        XCTAssertNil(body.subdata(in: imageRange.upperBound..<body.endIndex).range(of: image))
        XCTAssertThrowsError(try TypeSafeGateway.request(target: "test", frame: .init(data: image, width: 32, height: 32), config: .init(apiKey: "TEST_ONLY")))
    }
    func testHTTPFailureIsReturnedWithoutRetry() async throws {
        JevMockTransport.requests = 0
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [JevMockTransport.self]
        let session = URLSession(configuration: configuration)
        do {
            _ = try await TypeSafeGateway.locate(target: "search", frame: .init(data: Data([0xFF, 0xD8]), width: 450, height: 980), config: .init(apiKey: "TEST_ONLY"), session: session)
            XCTFail("Expected HTTP rejection")
        } catch { XCTAssertTrue(error.localizedDescription.contains("401")) }
        XCTAssertEqual(JevMockTransport.requests, 1)
    }
}

private final class JevMockTransport: URLProtocol {
    static var requests = 0
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests += 1
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: [:])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
