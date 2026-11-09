import XCTest
@testable import RoubaoModel

final class PhoneReportTests: XCTestCase {
    func payload(price: Double = 9999, evidence: String = UUID().uuidString) throws -> Data {
        let report: [String: Any] = ["title": "同规格比价", "summary": "当前可见价格", "items": [[
            "platform": "平台A", "product": "iPhone 18 Pro", "specification": "256GB 全新", "displayed_price": "¥9999",
            "price": price, "comparable": true, "conditions": "未计以旧换新", "evidence_id": evidence]]]
        let json = String(decoding: try JSONSerialization.data(withJSONObject: report), as: UTF8.self)
        return try JSONSerialization.data(withJSONObject: ["report_json": json])
    }
    func testReportRequiresValidEvidenceAndNonnegativePrice() throws {
        let report = try PhoneReport.decode(payload())
        XCTAssertEqual(report.items.first?.price, 9999)
        XCTAssertThrowsError(try PhoneReport.decode(payload(price: -1)))
        XCTAssertThrowsError(try PhoneReport.decode(payload(evidence: "../../pairingFile.plist")))
    }
    func testLiveStatusDoesNotExposeTypedTextOrQuestionContents() {
        let secret = "PRIVATE_TEST_SENTINEL"
        for name in ["type", "replace_text", "ask_user_question", "confirmed_action"] {
            let status = PhoneStepStatus.describe(name: name, arguments: ["text": secret, "question": secret, "description": secret])
            XCTAssertFalse(status.detail.contains(secret))
        }
    }
}
