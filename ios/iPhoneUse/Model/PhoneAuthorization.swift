import Foundation
import CryptoKit

enum PhoneApprovalDecision: Equatable {
    case approve, deny, unclear
    static func parse(_ raw: String) -> Self {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let json = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] {
            guard let answers = json["answers"] as? [[String: String]], answers.count == 1, let answer = answers[0]["answer"] else { return .unclear }
            text = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if ["确认", "确认执行", "确认发送"].contains(text) { return .approve }
        if ["取消", "不确认", "取消执行", "暂不执行", "暂不发送"].contains(text) { return .deny }
        return .unclear
    }
}

struct PhoneAuthorization: Codable, Sendable {
    var id = UUID().uuidString
    let description: String
    let bundleID: String
    let action: String
    let payload: String
    var status: String // approved / dispatched / submitted / unknown / denied
    let createdAt: Date

    struct Request {
        let description: String, bundleID: String, action: String, payload: String
        let reference: String?
        let newAuthorization: Bool
        init(_ raw: String) throws {
            guard let data = raw.data(using: .utf8), let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let description = root["description"] as? String, !description.isEmpty,
                  let bundleID = root["bundle_id"] as? String, !bundleID.isEmpty,
                  let action = root["action"] as? String, ["tap", "long_press", "type", "replace_text", "press_key"].contains(action),
                  let arguments = root["arguments"] as? [String: Any] else { throw PhoneHarnessError.invalidArguments }
            // A fresh visual position may change. Content/editing keys may not.
            let contents = arguments.filter { !["x", "y", "field_x", "field_y", "field_width", "field_height", "visual_target_id", "grounding_source", "_visual_region"].contains($0.key) }
            self.description = description; self.bundleID = bundleID; self.action = action
            payload = String(decoding: try JSONSerialization.data(withJSONObject: contents, options: [.sortedKeys, .withoutEscapingSlashes]), as: UTF8.self)
            reference = root["authorization_id"] as? String
            newAuthorization = root["new_authorization"] as? Bool ?? false
        }
        func matches(_ grant: PhoneAuthorization) -> Bool {
            description == grant.description && bundleID == grant.bundleID && action == grant.action && payload == grant.payload
        }
        func approved(_ allowed: Bool) -> PhoneAuthorization {
            .init(description: description, bundleID: bundleID, action: action, payload: payload, status: allowed ? "approved" : "denied", createdAt: .now)
        }
    }
    enum Check: Equatable { case askOwner, reuse(String), deny(String) }
    static func check(_ request: Request, grants: [Self]) -> Check {
        if request.newAuthorization { return .askOwner }
        let matching: Self?
        if let id = request.reference {
            guard let grant = grants.first(where: { $0.id == id }), request.matches(grant) else { return .deny("授权范围不匹配。请读取已有授权并使用原操作说明；若确实更换操作，必须申请新授权。") }
            matching = grant
        } else { matching = grants.last(where: request.matches) }
        guard let grant = matching else {
            if let related = grants.last(where: { $0.bundleID == request.bundleID && $0.action == request.action && $0.payload == request.payload }) {
                return .deny("同一应用/动作/内容已有授权记录（" + related.id + "，" + related.status + "）。改写操作说明不构成新操作。请复用原授权说明或先核对已提交结果；只有用户实际要求另一项操作时才设置 new_authorization。")
            }
            return .askOwner
        }
        switch grant.status {
        case "approved": return .reuse(grant.id)
        case "submitted": return .deny("这项授权对应动作已提交，不要再次执行或重复询问。请核对最新屏幕和实际结果；如用户明确要求再次执行，才申请新授权。")
        case "dispatched", "unknown": return .deny("此前动作送达状态未知，禁止重放。请先核对新截图和结果，不要重复请求相同确认。")
        default: return .deny("用户已拒绝此操作，不要重复询问。需要改变操作或由用户明确重新发起。")
        }
    }
}
