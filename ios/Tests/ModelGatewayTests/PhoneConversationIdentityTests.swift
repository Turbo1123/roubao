import XCTest
@testable import RoubaoModel
final class PhoneConversationIdentityTests: XCTestCase {
    func testFollowUpResumeMatchesFolderEvenWhenCurrentPromptDiffersFromChatTitle() {
        let original = UUID(), folder = UUID().uuidString
        let records: [PhoneConversationIdentity] = [.init(id: original, folder: folder, originalPrompt: "比较三个平台并生成PDF", date: .distantPast), .init(id: UUID(), folder: UUID().uuidString, originalPrompt: "给我发微信", date: .now)]
        XCTAssertEqual(PhoneConversationIdentity.restoredID(folder: folder, prompt: "给我发微信", records: records), original)
    }
    func testEstablishedIdentityWinsOverAccidentalDuplicateOfSameTaskFolder() {
        let original = UUID(), folder = UUID().uuidString
        let records: [PhoneConversationIdentity] = [.init(id: UUID(), folder: folder, originalPrompt: "后续发送", date: .now), .init(id: original, folder: folder, originalPrompt: "原任务", date: .distantPast)]
        XCTAssertEqual(PhoneConversationIdentity.restoredID(folder: folder, prompt: "后续发送", records: records), original)
        XCTAssertNil(PhoneConversationIdentity.restoredID(folder: UUID().uuidString, prompt: "原任务", records: records))
    }
}
