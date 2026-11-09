import Foundation
import CoreGraphics
import ImageIO

/// Detects unchanged observations after attempted UI actions. This is a recovery
/// signal for the primary model, never a proof that an action was not delivered.
struct PhoneStallMonitor {
    struct Snapshot {
        let pixels: [UInt8]?
        let text: Set<String>
        let anchors: Set<String>
        let fallback: Data
        init(_ frame: PhoneFrame) {
            text = Set(frame.text.filter { $0.y > frame.height * 0.05 && $0.y < frame.height * 0.97 }
                .map { $0.text.precomposedStringWithCanonicalMapping.split(whereSeparator: \.isWhitespace).joined() }.filter { !$0.isEmpty })
            anchors = Set(frame.text.filter { $0.y > frame.height * 0.18 && $0.y < frame.height * 0.97 }.map {
                let value = $0.text.precomposedStringWithCanonicalMapping.split(whereSeparator: \.isWhitespace).joined()
                return "\(Int(($0.x + $0.width / 2) / frame.width * 12)):\(Int(($0.y + $0.height / 2) / frame.height * 24)):" + value
            })
            fallback = frame.data
            if let source = CGImageSourceCreateWithData(frame.data as CFData, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
               let cropped = image.cropping(to: CGRect(x: 0, y: Double(image.height) * 0.05, width: Double(image.width), height: Double(image.height) * 0.92)) {
                var samples = [UInt8](repeating: 0, count: 32 * 64)
                let rendered = samples.withUnsafeMutableBytes { bytes -> Bool in
                    guard let context = CGContext(data: bytes.baseAddress, width: 32, height: 64, bitsPerComponent: 8, bytesPerRow: 32, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: 0) else { return false }
                    context.interpolationQuality = .low
                    context.draw(cropped, in: CGRect(x: 0, y: 0, width: 32, height: 64))
                    return true
                }
                pixels = rendered ? samples : nil
            } else { pixels = nil }
        }
        /// Progress detection tolerates small, disconnected dynamic areas. This
        /// is deliberately separate from strict whole-frame freshness checks.
        func samePage(as other: Snapshot) -> Bool {
            if resembles(other) { return true }
            let common = anchors.intersection(other.anchors).count
            guard common >= 8, Double(common) / Double(max(1, max(anchors.count, other.anchors.count))) >= 0.60,
                  let pixels, let old = other.pixels else { return false }
            var stable = 0
            for row in 0..<8 {
                for column in 0..<8 {
                    var difference = 0.0
                    for y in row*8..<(row+1)*8 { for x in column*4..<(column+1)*4 { difference += abs(Double(pixels[y*32+x]) - Double(old[y*32+x])) } }
                    if difference / 32 <= 8 { stable += 1 }
                }
            }
            return stable >= 42 // at least 65% of spatial tiles retained
        }
        func resembles(_ other: Snapshot) -> Bool {
            let union = text.union(other.text)
            let textSimilarity = union.isEmpty ? 1 : Double(text.intersection(other.text).count) / Double(union.count)
            guard textSimilarity >= 0.9 else { return false }
            if let pixels, let old = other.pixels {
                let difference = zip(pixels, old).reduce(0.0) { $0 + abs(Double($1.0) - Double($1.1)) } / Double(pixels.count)
                return difference <= (union.isEmpty ? 2 : 4)
            }
            return fallback == other.fallback
        }
    }
    private var previous: Snapshot?
    private var attempted: String?
    private var attemptedGrounding = false
    private(set) var unchangedGroundings = 0
    private(set) var unchangedActions = 0
    private(set) var recentActions: [String] = []
    var requiresMultimodal: Bool { unchangedGroundings >= 2 }
    var requiresPrimary: Bool { unchangedActions >= 4 }
    var feedback: String? {
        guard requiresPrimary else { return nil }
        return "STAGNATION: \(unchangedActions) consecutive UI actions produced no visible progress. Recent attempts: \(recentActions.joined(separator: ", ")). The old JEV plan is suspended. YOU (the primary model) must inspect the current screenshot and tool receipts, identify why the strategy failed and choose a different action/path, wait with reason, or ask the owner. Do not repeat the same blind taps or infer that a file was sent. A file visible in an attachment picker is NOT proof of a sent chat message. This signal is uncertain; do not replay any possibly delivered mutation."
    }
    mutating func record(_ name: String, arguments: Data) {
        guard ["tap", "tap_text", "tap_target", "tap_visual_target", "long_press", "swipe", "swipe_up", "swipe_down", "type", "replace_text", "press_key", "open_app", "home", "wait", "confirmed_action"].contains(name) else { return }
        let args = (try? JSONSerialization.jsonObject(with: arguments)) as? [String: Any] ?? [:]
        let detail = args.filter { ["x", "y", "id", "target", "bundle_id", "key"].contains($0.key) }.map { $0.key + "=" + String(describing: $0.value) }.sorted().joined(separator: " ")
        attemptedGrounding = ["tap", "tap_text", "tap_target", "tap_visual_target", "long_press", "confirmed_action"].contains(name)
        attempted = name + (detail.isEmpty ? "" : " " + detail)
    }
    mutating func observe(_ frame: PhoneFrame) -> Bool {
        let current = Snapshot(frame)
        defer { previous = current; attempted = nil; attemptedGrounding = false }
        guard let previous else { return false }
        if !current.samePage(as: previous) { unchangedActions = 0; unchangedGroundings = 0; recentActions = []; return false }
        guard let attempted else { return false }
        unchangedActions += 1
        if attemptedGrounding { unchangedGroundings += 1 }
        recentActions = Array((recentActions + [attempted]).suffix(4))
        return unchangedActions >= 4 && unchangedActions % 4 == 0
    }
}
