import Foundation
import CoreGraphics

/// Conservative guard for observed final-action labels, across all apps.
/// OCR cannot prove every UI action's semantics; this covers known visible
/// commit controls and returns uncertainty to the primary model for approval.
enum PhoneFinalActionGuard {
    static let labels: Set<String> = ["发送", "發送", "send", "发布", "發佈", "发表", "發表", "post", "publish", "回复", "回覆", "reply", "删除", "刪除", "delete", "支付", "付款", "pay", "购买", "購買", "buynow", "placeorder", "提交", "submit", "收藏", "favorite"]
    static func normalized(_ value: String) -> String { value.lowercased().filter { !$0.isWhitespace } }
    static func blockedLabel(name: String, arguments: [String: Any], frame: PhoneFrame) -> String? {
        let controls = frame.text.filter { $0.confidence >= 0.4 && labels.contains(normalized($0.text)) }
        guard !controls.isEmpty else { return nil }
        func coordinate(_ key: String) -> Double? { (arguments[key] as? NSNumber)?.doubleValue }
        var point: CGPoint?
        switch name {
        case "tap", "long_press": if let x = coordinate("x"), let y = coordinate("y") { point = CGPoint(x: x, y: y) }
        case "tap_text":
            if let id = arguments["id"] as? String, let line = frame.text.first(where: { $0.id == id }) { point = CGPoint(x: line.x + line.width / 2, y: line.y + line.height / 2) }
        case "press_key", "type":
            let isReturn = name == "press_key" ? arguments["key"] as? String == "return" : arguments["text"] as? String == "\n"
            if isReturn { return controls.first(where: { $0.y > frame.height * 0.4 && ["send", "发送", "發送", "submit", "提交"].contains(normalized($0.text)) })?.text }
        default: break
        }
        guard let point else { return nil }
        return controls.first { line in
            CGRect(x: line.x, y: line.y, width: line.width, height: line.height)
                .insetBy(dx: -max(line.height * 1.1, 10), dy: -max(line.height * 0.6, 8)).contains(point)
        }?.text
    }
}
