import Foundation

/// A primary-model image grounding receipt, not an executed action.
struct PhoneVisualTarget: Codable, Sendable {
    var id = UUID().uuidString
    let target: String
    let left: Double, top: Double, right: Double, bottom: Double
    let confidence: Double
    let evidenceID: String?
    var x: Double { (left + right) / 2 }
    var y: Double { (top + bottom) / 2 }
    var valid: Bool {
        UUID(uuidString: id) != nil && !target.isEmpty && [left, top, right, bottom, confidence].allSatisfy { $0.isFinite && (0...1).contains($0) }
            && left < right && top < bottom && confidence >= 0.85
    }
    static func parse(_ reply: [String: Any], target: String, evidenceID: String?) throws -> Self {
        guard let calls = reply["tool_calls"] as? [[String: Any]], calls.count == 1,
              let f = calls[0]["function"] as? [String: Any], f["name"] as? String == "visual_target",
              let args = f["arguments"] as? String,
              let json = try? JSONSerialization.jsonObject(with: Data(args.utf8)) as? [String: Any],
              json["visible"] as? Bool == true,
              let left = json["left"] as? Double, let top = json["top"] as? Double,
              let right = json["right"] as? Double, let bottom = json["bottom"] as? Double,
              let confidence = json["confidence"] as? Double else { throw JevError.uncertain }
        let result = Self(target: target, left: left, top: top, right: right, bottom: bottom, confidence: confidence, evidenceID: evidenceID)
        guard result.valid else { throw JevError.uncertain }
        return result
    }
    static let tools: [[String: Any]] = [["type": "function", "function": [
        "name": "visual_target", "description": "Locate the actual tappable control using ONLY the screenshot. Return normalized bounds, top-left origin. For an icon with a caption, locate the icon/control hit area, not the caption's text box. If ambiguous, occluded, absent or uncertain set visible=false. This only observes, never clicks.",
        "parameters": ["type": "object", "properties": ["visible": ["type": "boolean"],
            "left": ["type": "number"], "top": ["type": "number"], "right": ["type": "number"], "bottom": ["type": "number"],
            "confidence": ["type": "number"], "reason": ["type": "string"]],
            "required": ["visible", "left", "top", "right", "bottom", "confidence", "reason"]]
    ]]]
}
