import Foundation
import CryptoKit

struct PhoneTaskFile: Codable, Sendable, Equatable {
    let id: String
    let filename: String
    let title: String
    let mediaType: String
    let bytes: Int
    let pages: Int?
    let summary: String?
    var location: String { "肉包→任务文件" }
    var relativePath: String { "Documents/Reports/" + filename }
    static func identifier(_ filename: String) -> String {
        "file_" + SHA256.hash(data: Data(filename.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }
    var valid: Bool { filename == URL(fileURLWithPath: filename).lastPathComponent && !filename.isEmpty && id == Self.identifier(filename) }
}
