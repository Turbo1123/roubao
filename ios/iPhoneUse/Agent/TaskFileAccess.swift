import Foundation
import PDFKit
import UIKit

@MainActor
enum TaskFileAccess {
    static func list(directory: URL, indexURL: URL?, history: String, latest: URL?) -> [PhoneTaskFile] {
        let previous = indexURL.flatMap { try? Data(contentsOf: $0) }.flatMap { try? JSONDecoder().decode([PhoneTaskFile].self, from: $0) } ?? []
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey], options: [.skipsHiddenFiles])) ?? []
        let owned = Set(previous.filter(\.valid).map(\.filename))
        let result = urls.compactMap { url -> PhoneTaskFile? in
            let filename = url.lastPathComponent
            guard owned.contains(filename) || history.contains(filename) || latest?.lastPathComponent == filename,
                  let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]), values.isRegularFile == true,
                  url.resolvingSymlinksInPath().deletingLastPathComponent() == directory.resolvingSymlinksInPath() else { return nil }
            let old = previous.first { $0.filename == filename }
            let pdf = url.pathExtension.lowercased() == "pdf" ? PDFDocument(url: url) : nil
            return .init(id: PhoneTaskFile.identifier(filename), filename: filename,
                title: pdf?.documentAttributes?[PDFDocumentAttribute.titleAttribute] as? String ?? old?.title ?? url.deletingPathExtension().lastPathComponent,
                mediaType: pdf == nil ? "application/octet-stream" : "application/pdf", bytes: values.fileSize ?? 0,
                pages: pdf?.pageCount ?? old?.pages, summary: old?.summary)
        }.sorted { $0.filename < $1.filename }
        if let indexURL {
            try? FileManager.default.createDirectory(at: indexURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? JSONEncoder().encode(result).write(to: indexURL, options: .atomic)
        }
        return result
    }
    static func resolve(_ id: String, files: [PhoneTaskFile], directory: URL) throws -> URL {
        guard let file = files.first(where: { $0.id == id && $0.valid }) else { throw PhoneHarnessError.invalidArguments }
        let url = directory.appendingPathComponent(file.filename)
        guard FileManager.default.fileExists(atPath: url.path), url.resolvingSymlinksInPath().deletingLastPathComponent() == directory.resolvingSymlinksInPath() else { throw PhoneHarnessError.invalidArguments }
        return url
    }
    static func read(_ url: URL) throws -> String {
        let full: String
        if url.pathExtension.lowercased() == "pdf" {
            guard let document = PDFDocument(url: url) else { throw PhoneHarnessError.invalidArguments }
            full = document.string ?? "此 PDF 没有可提取的文字。"
        } else { full = try String(contentsOf: url, encoding: .utf8) }
        let result: [String: Any] = ["file": url.lastPathComponent, "text": String(full.prefix(18_000)), "truncated": full.count > 18_000]
        return String(decoding: try JSONSerialization.data(withJSONObject: result), as: UTF8.self)
    }
    static func share(_ url: URL) async throws {
        guard var presenter = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController else { throw PhoneHarnessError.invalidArguments }
        while let current = presenter.presentedViewController { presenter = current }
        presenter.view.endEditing(true)
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        controller.popoverPresentationController?.sourceView = presenter.view
        controller.popoverPresentationController?.sourceRect = CGRect(x: presenter.view.bounds.midX, y: 80, width: 1, height: 1)
        await withCheckedContinuation { continuation in presenter.present(controller, animated: true) { continuation.resume() } }
    }
}
