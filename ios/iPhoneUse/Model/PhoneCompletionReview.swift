import Foundation

enum PhoneCompletionReview {
    static let tools: [[String: Any]] = [["type": "function", "function": [
        "name": "completion_check",
        "description": "Judge whether the LATEST user request is actually complete, based on observed evidence and tool receipts. Earlier successful tasks do not satisfy a new follow-up. A preview/attachment picker is not proof a message/file was sent. Owner corrections override earlier unsupported assumptions. If incomplete, the same primary model must continue or ask the owner, not silently stop.",
        "parameters": ["type": "object", "properties": [
            "status": ["type": "string", "enum": ["complete", "incomplete"]],
            "evidence_or_remaining_work": ["type": "string"]
        ], "required": ["status", "evidence_or_remaining_work"]]
    ]]]
    static func rejection(_ reply: [String: Any]) -> String? {
        guard let calls = reply["tool_calls"] as? [[String: Any]], calls.count == 1,
              let function = calls[0]["function"] as? [String: Any], function["name"] as? String == "completion_check",
              let args = function["arguments"] as? String,
              let json = try? JSONSerialization.jsonObject(with: Data(args.utf8)) as? [String: String],
              let reason = json["evidence_or_remaining_work"], !reason.isEmpty else {
            return "完成核对未得到有效结论。请核对最新请求和屏幕后继续，必要时询问用户。"
        }
        return json["status"] == "complete" ? nil : "最新请求尚未完成：" + reason + "。继续同一任务或在肉包询问用户；不要以较早的任务成果结束。"
    }
}
