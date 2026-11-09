import Foundation
import CoreGraphics

enum PhoneOwnerControls {
    static func blocks(name: String, arguments: [String: Any], frame: PhoneFrame, region: CGRect?) -> Bool {
        var bounds = region
        if bounds == nil, let line = frame.text.first(where: { $0.text.replacingOccurrences(of: " ", with: "") == "停止任务" }) {
            bounds = CGRect(x: 0.02, y: line.y / frame.height - 0.02, width: 0.96, height: line.height / frame.height + 0.04)
        }
        guard let bounds else { return false }
        func point(_ x: String, _ y: String) -> CGPoint? {
            guard let a = (arguments[x] as? NSNumber)?.doubleValue, let b = (arguments[y] as? NSNumber)?.doubleValue else { return nil }
            return CGPoint(x: a / frame.width, y: b / frame.height)
        }
        switch name {
        case "tap", "long_press": return point("x", "y").map { bounds.contains($0) } ?? false
        case "tap_text":
            guard let id = arguments["id"] as? String, let line = frame.text.first(where: { $0.id == id }) else { return false }
            return bounds.contains(CGPoint(x: (line.x + line.width / 2) / frame.width, y: (line.y + line.height / 2) / frame.height))
        case "swipe": return [point("x1", "y1"), point("x2", "y2")].compactMap { $0 }.contains { bounds.contains($0) }
        default: return false
        }
    }
}
