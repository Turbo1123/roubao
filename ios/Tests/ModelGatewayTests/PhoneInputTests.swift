import XCTest
@testable import RoubaoModel

final class PhoneInputTests: XCTestCase {
    func line(_ text: String, y: Double = 30) -> PhoneScreenText {
        .init(id: "t1", text: text, x: 20, y: y, width: 160, height: 20, confidence: 0.9)
    }
    var region: PhoneTextRegion {
        PhoneTextRegion(arguments: ["field_x": 10, "field_y": 20, "field_width": 200, "field_height": 50], frameWidth: 450, frameHeight: 900)!
    }
    func testTextOutsideInputFieldNeverVerifiesPaste() {
        XCTAssertEqual(PhoneTextEvidence.status(expected: "AI 实用技巧", lines: [line("AI 实用技巧", y: 300), line("旧文字")], region: region), "unverified_ocr_mismatch_or_clipping")
        XCTAssertEqual(PhoneTextEvidence.status(expected: "AI 实用技巧", lines: [line("AI 实用技巧")], region: nil), "not_checked_no_field_region")
    }
    func testEvidenceRequiresFullRegionTextAndPreservesPunctuation() {
        XCTAssertEqual(PhoneTextEvidence.status(expected: "你好 AI 123!", lines: [line("你好 AI 123!")], region: region), "ocr_region_matches")
        XCTAssertNotEqual(PhoneTextEvidence.status(expected: "你好 AI 123!", lines: [line("你好 AI 123")], region: region), "ocr_region_matches")
        XCTAssertNotEqual(PhoneTextEvidence.status(expected: "AI", lines: [line("AI 工具")], region: region), "ocr_region_matches")
    }
    func testInvalidAndPartialRegionsRejected() {
        XCTAssertNil(PhoneTextRegion(arguments: ["field_x": 10], frameWidth: 450, frameHeight: 900))
        XCTAssertNil(PhoneTextRegion(arguments: ["field_x": 400, "field_y": 20, "field_width": 200, "field_height": 50], frameWidth: 450, frameHeight: 900))
    }
    func testLiteralInputAndSubmissionHaveSeparateTools() {
        let tools = ModelGateway.tools.compactMap { $0["function"] as? [String: Any] }
        let names = tools.compactMap { $0["name"] as? String }
        XCTAssertTrue(Set(["type", "replace_text", "press_key", "tap_text", "tap_target"]).isSubset(of: Set(names)))
        let parameters = tools.first { $0["name"] as? String == "type" }?["parameters"] as! [String: Any]
        XCTAssertEqual(parameters["required"] as? [String], ["text"])
    }
}
