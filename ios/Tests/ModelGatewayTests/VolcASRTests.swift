import XCTest
@testable import RoubaoModel

final class VolcASRTests: XCTestCase {
    func testGzipRoundTripAndFinalCumulativeResult() throws {
        let original = Data(String(repeating: "中文语音测试", count: 3000).utf8)
        XCTAssertEqual(try VolcASRCodec.gzip(VolcASRCodec.gzip(original), decompress: true), original)
        let partial = try VolcASRCodec.frame(type: 9, serialization: 1, payload: Data(#"{"result":{"text":"刚才的"}}"#.utf8))
        XCTAssertEqual(try VolcASRCodec.parse(partial).text, "刚才的")
        XCTAssertFalse(try VolcASRCodec.parse(partial).final)
        let final = try VolcASRCodec.frame(type: 9, flags: 2, serialization: 1, payload: Data(#"{"result":[{"text":"修改刚才的报告。"}]}"#.utf8))
        XCTAssertEqual(try VolcASRCodec.parse(final).text, "修改刚才的报告。")
        XCTAssertTrue(try VolcASRCodec.parse(final).final)
    }
    func testMalformedTruncatedAndServiceErrorFramesFailWithoutRawPayload() throws {
        for data in [Data(), Data([0x11, 0x92, 0x11, 0, 255, 255, 255, 255]), Data(repeating: 0, count: 10)] {
            XCTAssertThrowsError(try VolcASRCodec.parse(data))
        }
        XCTAssertThrowsError(try VolcASRCodec.gzip(Data([1,2,3]), decompress: true))
        XCTAssertThrowsError(try VolcASRCodec.parse(Data([0x11,0xf0,0,0,0,0,0,17]))) { error in
            XCTAssertTrue(error.localizedDescription.contains("17"))
        }
    }
}
