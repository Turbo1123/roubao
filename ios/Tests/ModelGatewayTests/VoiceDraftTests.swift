import XCTest
@testable import RoubaoModel
final class VoiceDraftTests: XCTestCase {
    func testCumulativeRevisionsReplaceWithoutDuplicationAndPreserveTypedPrefix() {
        var draft = VoiceDraft(); draft.begin("请")
        let partial = draft.update("继续刚才", current: "请")
        XCTAssertEqual(partial, "请 继续刚才")
        let final = draft.update("继续修改刚才的报告。", current: partial)
        XCTAssertEqual(final, "请 继续修改刚才的报告。")
        XCTAssertEqual(draft.update("继续修改刚才的报告。", current: final), final)
    }
    func testManualCorrectionSurvivesLateFinalAndNextRecordingAppends() {
        var draft = VoiceDraft(); draft.begin("")
        _ = draft.update("修改爆告", current: "")
        XCTAssertEqual(draft.update("修改报告。", current: "修改报告并保留原版"), "修改报告并保留原版")
        draft.begin("修改报告并保留原版")
        XCTAssertEqual(draft.update("另存文件", current: "修改报告并保留原版"), "修改报告并保留原版 另存文件")
    }
}
