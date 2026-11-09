import Foundation

struct PhoneScreenText: Codable, Sendable, Equatable {
    let id: String
    let text: String
    let x: Double
    let y: Double
    let width: Double
    let height: Double
    let confidence: Double
}

struct PhoneTextRegion: Sendable {
    let x: Double, y: Double, width: Double, height: Double

    init?(arguments: [String: Any], frameWidth: Double, frameHeight: Double) {
        let keys = ["field_x", "field_y", "field_width", "field_height"]
        let values = keys.compactMap { (arguments[$0] as? NSNumber)?.doubleValue }
        guard values.count == 4, values.allSatisfy(\.isFinite), values[0] >= 0, values[1] >= 0,
              values[2] > 0, values[3] > 0, values[0] + values[2] <= frameWidth,
              values[1] + values[3] <= frameHeight else { return nil }
        (x, y, width, height) = (values[0], values[1], values[2], values[3])
    }

    func contains(_ line: PhoneScreenText) -> Bool {
        let cx = line.x + line.width / 2, cy = line.y + line.height / 2
        return cx >= x && cx <= x + width && cy >= y && cy <= y + height
    }
}

enum PhoneTextEvidence {
    // OCR is supporting evidence, not an accessibility read of the field value.
    static func status(expected: String, lines: [PhoneScreenText], region: PhoneTextRegion?) -> String {
        guard let region else { return "not_checked_no_field_region" }
        let candidates = lines.filter { $0.confidence >= 0.4 && region.contains($0) }
        guard !candidates.isEmpty else { return "unverified_no_ocr_text" }
        func canonical(_ value: String) -> String {
            value.precomposedStringWithCanonicalMapping.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        }
        let target = canonical(expected)
        guard !target.isEmpty else { return "unverified_empty_text" }
        let actual = candidates.sorted { $0.y == $1.y ? $0.x < $1.x : $0.y < $1.y }
        let observed = actual.map(\.text).joined(separator: " ")
        return canonical(observed) == target ? "ocr_region_matches" : "unverified_ocr_mismatch_or_clipping"
    }
}
