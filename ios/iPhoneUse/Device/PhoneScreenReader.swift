import Foundation
import Vision

enum PhoneScreenReader {
    static func read(_ data: Data, width: Double, height: Double) -> [PhoneScreenText] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["zh-Hans", "en-US"]
        request.usesLanguageCorrection = false
        do { try VNImageRequestHandler(data: data).perform([request]) } catch { return [] }
        return (request.results ?? []).flatMap { observation -> [PhoneScreenText] in
            guard let value = observation.topCandidates(1).first, value.confidence >= 0.4 else { return [] }
            func item(_ text: String, _ box: CGRect) -> PhoneScreenText {
                .init(id: "", text: text, x: box.minX * width, y: (1 - box.maxY) * height,
                      width: box.width * width, height: box.height * height, confidence: Double(value.confidence))
            }
            let text = value.string
            let words = text.split(whereSeparator: \.isWhitespace)
            if words.count > 1 {
                let split = words.compactMap { word -> PhoneScreenText? in
                    guard let box = try? value.boundingBox(for: word.startIndex..<word.endIndex), box.boundingBox.width > 0 else { return nil }
                    return item(String(word), box.boundingBox)
                }
                if split.count == words.count { return split }
            }
            return [item(text, observation.boundingBox)]
        }.sorted { $0.y < $1.y }.prefix(60).enumerated().map { index, line in
            .init(id: "t\(index + 1)", text: line.text, x: line.x, y: line.y,
                  width: line.width, height: line.height, confidence: line.confidence)
        }
    }
}
