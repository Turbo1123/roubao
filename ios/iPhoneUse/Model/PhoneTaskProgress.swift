import Foundation

struct PhoneTaskProgress: Codable, Sendable {
    struct Item: Codable, Sendable {
        let id: String
        var title: String
        var status: String
        var details: String?
        var evidence_id: String?
    }
    var items: [Item] = []
    var reportFile: String? = nil
    var reportPending: Bool? = nil
    var stepAllowance: Int? = nil
    mutating func merge(_ json: String) throws {
        let updates = try JSONDecoder().decode([Item].self, from: Data(json.utf8))
        guard (1...30).contains(updates.count), Set(updates.map(\.id)).count == updates.count,
              updates.allSatisfy({ row in
                  !row.id.isEmpty && row.id.count <= 80 && !row.title.isEmpty && row.title.count <= 200
                  && ["pending", "complete", "blocked"].contains(row.status) && (row.details?.count ?? 0) <= 8000
                  && (row.evidence_id == nil || UUID(uuidString: row.evidence_id!) != nil)
                  && (row.status != "complete" || !(row.details ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
              }) else { throw PhoneHarnessError.invalidArguments }
        for update in updates {
            if let index = items.firstIndex(where: { $0.id == update.id }) { items[index] = update }
            else { items.append(update) }
        }
        guard items.count <= 30 else { throw PhoneHarnessError.invalidArguments }
    }
    func completionFeedback(needsChecklist: Bool, needsReport: Bool) -> String? {
        var reasons: [String] = []
        if needsChecklist && items.isEmpty { reasons.append("尚未建立任务清单，请先根据用户要求及已提交答复，用 update_task_progress 记录所有待对比的平台。") }
        let unfinished = items.filter { $0.status != "complete" }
        if !unfinished.isEmpty { reasons.append("尚未完成：" + unfinished.map { $0.title + "（" + $0.status + "）" }.joined(separator: "、")) }
        if needsReport && (reportFile == nil || reportPending == true) { reasons.append("尚未成功生成用户要求的 PDF，请调用 create_pdf_report；不要宣称文件已生成。") }
        return reasons.isEmpty ? nil : reasons.joined(separator: "\n")
    }
}
