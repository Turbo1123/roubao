import Foundation

struct PhoneQuestionOption: Codable, Equatable, Sendable {
    let label: String
    var description: String? = nil
}
struct PhoneQuestionItem: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let prompt: String
    var options: [PhoneQuestionOption]? = nil
}
struct PhoneQuestion: Codable, Equatable, Identifiable, Sendable {
    var id = UUID()
    let title: String
    let items: [PhoneQuestionItem]
    let taskPrompt: String
    var kind: String? = nil
    var conversationID: UUID? = nil
    var taskFolder: String? = nil
    var approvalArguments: String? = nil
    var createdAt = Date()

    static func decode(_ data: Data, taskPrompt: String) throws -> PhoneQuestion {
        struct Arguments: Decodable {
            let question: String
            let questions: [PhoneQuestionItem]?
            let options: [PhoneQuestionOption]?
            let questions_json: String?
        }
        let args = try JSONDecoder().decode(Arguments.self, from: data)
        let items: [PhoneQuestionItem]
        if let json = args.questions_json { items = try JSONDecoder().decode([PhoneQuestionItem].self, from: Data(json.utf8)) }
        else { items = args.questions ?? [.init(id: "answer", prompt: args.question, options: args.options)] }
        guard !args.question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, args.question.count <= 3000,
              (1...3).contains(items.count), Set(items.map(\.id)).count == items.count,
              items.allSatisfy({ item in
                  !item.id.isEmpty && item.id.count <= 80 && !item.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && item.prompt.count <= 2000
                  && (item.options ?? []).count <= 6
                  && Set((item.options ?? []).map(\.label)).count == (item.options ?? []).count
                  && (item.options ?? []).allSatisfy { !$0.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.label.count <= 160 && ($0.description?.count ?? 0) <= 500 }
              }) else { throw PhoneHarnessError.invalidArguments }
        return .init(title: args.question, items: items, taskPrompt: taskPrompt)
    }
    static func text(_ text: String, taskPrompt: String) -> PhoneQuestion {
        .init(title: text, items: [.init(id: "answer", prompt: text)], taskPrompt: taskPrompt)
    }
    var continuationText: String {
        let prompts = items.map { $0.prompt }.joined(separator: "\n")
        return "继续当前任务，不要重启或切换任务。你在等待用户答复时应用被中断。以下是你已经提出的问题（不是新的任务）：\n" + title + "\n" + prompts
    }
    func response(_ answers: [String: String]) throws -> String {
        guard items.allSatisfy({ !(answers[$0.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
            throw PhoneHarnessError.invalidArguments
        }
        let selected = items.map { ["id": $0.id, "question": $0.prompt, "answer": answers[$0.id]!.trimmingCharacters(in: .whitespacesAndNewlines)] }
        return String(decoding: try JSONSerialization.data(withJSONObject: ["answers": selected], options: [.sortedKeys]), as: UTF8.self)
    }
}

struct PendingPhoneQuestion: Codable, Sendable {
    let question: PhoneQuestion
    var answer: String? = nil
    var draftAnswers: [String: String]? = nil
    var draftStep: Int? = nil
    var continuationText: String? {
        guard let answer, !answer.isEmpty else { return nil }
        return question.continuationText + "\n用户已提交的答复（原样，用户可以纠正你的判断，不要将其当作已成功执行的证明）：\n" + answer
            + "\n先重新观察当前屏幕，继续原任务；不要重放未确认的发送，也不要重复询问已答过的问题。"
    }
}

struct PhoneQuestionStore {
    let url: URL
    static var live: PhoneQuestionStore {
        .init(url: URL.applicationSupportDirectory.appendingPathComponent("Questions/pending.json"))
    }
    func load() -> PendingPhoneQuestion? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(PendingPhoneQuestion.self, from: data)
    }
    func save(_ value: PendingPhoneQuestion) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
        var copy = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try copy.setResourceValues(values)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        #endif
    }
    func clear() { try? FileManager.default.removeItem(at: url) }
}
