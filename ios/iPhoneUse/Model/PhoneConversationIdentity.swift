import Foundation

struct PhoneConversationIdentity {
    let id: UUID
    let folder: String?
    let originalPrompt: String
    let date: Date
    static func restoredID(folder: String?, prompt: String, records: [Self]) -> UUID? {
        let matches = records.filter { record in
            if let folder { return record.folder == folder }
            return record.originalPrompt == prompt
        }
        // A follow-up changes the current request, not the conversation identity.
        // Recover the established record if an older app created a duplicate.
        return matches.min { $0.date < $1.date }?.id
    }
}
