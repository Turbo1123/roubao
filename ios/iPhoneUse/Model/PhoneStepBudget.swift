import Foundation

enum PhoneBudgetDecision: Sendable, Equatable { case extend, pause, stop, unclear }
enum PhoneStepBudget {
    static let initial = 100
    static let increment = 50
    static let continueLabel = "继续，再增加50步"
    static let pauseLabel = "先暂停，保留进度"
    static let stopLabel = "停止任务"
    static func decision(_ response: String) -> PhoneBudgetDecision {
        var answer = response.trimmingCharacters(in: .whitespacesAndNewlines)
        if let data = response.data(using: .utf8), let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let values = object["answers"] as? [[String: String]], let value = values.first(where: { $0["id"] == "step_budget" })?["answer"] {
            answer = value.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if [continueLabel, "继续", "加50步", "增加50步"].contains(answer) { return .extend }
        if [pauseLabel, "暂停", "稍后继续", "暂不继续"].contains(answer) { return .pause }
        if [stopLabel, "停止", "结束"].contains(answer) { return .stop }
        return .unclear
    }
}
