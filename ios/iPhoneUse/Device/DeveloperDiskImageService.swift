//
//  DeveloperDiskImageService.swift
//  iPhoneUse
//

import Foundation

final class DeveloperDiskImageService {
    static let shared = DeveloperDiskImageService()
    static let directoryURL = URL.documentsDirectory.appendingPathComponent("DDI", isDirectory: true)

    static var filesAreReady: Bool {
        downloadItems.allSatisfy {
            FileManager.default.fileExists(atPath: directoryURL.appendingPathComponent($0.fileName).path)
        } && (try? String(contentsOf: mountMethodURL, encoding: .utf8)) == mountMethod.rawValue
    }

    static var usesCryptexDDI: Bool {
        mountMethod == .cryptex
    }

    private let fileManager: FileManager
    private let session: URLSession

    init(fileManager: FileManager = .default, session: URLSession = .shared) {
        self.fileManager = fileManager
        self.session = session
    }

    func downloadMissingFiles() async throws {
        let installedMethod = try? String(contentsOf: Self.mountMethodURL, encoding: .utf8)
        let replaceFiles = installedMethod != Self.mountMethod.rawValue

        if !Self.usesCryptexDDI {
            try removeCryptexOnlyFiles()
        }

        for item in Self.downloadItems {
            let destinationURL = Self.directoryURL.appendingPathComponent(item.fileName)
            guard replaceFiles || !fileManager.fileExists(atPath: destinationURL.path) else {
                continue
            }
            try await downloadFile(from: item.urlString, to: destinationURL)
        }
        try Self.mountMethod.rawValue.write(to: Self.mountMethodURL, atomically: true, encoding: .utf8)
    }

    func downloadFile(from urlString: String, to destinationURL: URL) async throws {
        guard let url = URL(string: urlString),
              url.scheme?.lowercased() == "https" else {
            throw DDIDownloadError.invalidURL(urlString)
        }

        let (temporaryURL, response) = try await session.download(from: url)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw DDIDownloadError.invalidResponse
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw DDIDownloadError.badStatus(httpResponse.statusCode)
        }

        try fileManager.createDirectory(
            at: destinationURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        if fileManager.fileExists(atPath: destinationURL.path) {
            try fileManager.removeItem(at: destinationURL)
        }
        try fileManager.moveItem(at: temporaryURL, to: destinationURL)
    }

    func redownload(progressHandler: ((Double, String) -> Void)? = nil) async throws {
        let totalStages = Double(Self.downloadItems.count + 1)
        var completedStages = 0.0

        progressHandler?(0.0, "Removing existing DDI files...")
        if fileManager.fileExists(atPath: Self.mountMethodURL.path) {
            try fileManager.removeItem(at: Self.mountMethodURL)
        }
        if !Self.usesCryptexDDI {
            try removeCryptexOnlyFiles()
        }
        let completionMarker = Self.directoryURL.appendingPathComponent("Image.dmg.root_hash")
        if fileManager.fileExists(atPath: completionMarker.path) {
            try fileManager.removeItem(at: completionMarker)
        }
        for item in Self.downloadItems {
            let fileURL = Self.directoryURL.appendingPathComponent(item.fileName)
            if fileManager.fileExists(atPath: fileURL.path) {
                try fileManager.removeItem(at: fileURL)
            }
        }

        completedStages += 1.0
        progressHandler?(completedStages / totalStages, "Starting downloads...")

        for item in Self.downloadItems {
            progressHandler?(completedStages / totalStages, "Downloading \(item.name)...")
            let destinationURL = Self.directoryURL.appendingPathComponent(item.fileName)
            try await downloadFile(from: item.urlString, to: destinationURL)
            completedStages += 1.0
            progressHandler?(completedStages / totalStages, "\(item.name) ready")
        }

        try Self.mountMethod.rawValue.write(to: Self.mountMethodURL, atomically: true, encoding: .utf8)

        progressHandler?(1.0, "DDI download complete.")
    }

    private func removeCryptexOnlyFiles() throws {
        for fileName in Self.cryptexOnlyFileNames {
            let fileURL = Self.directoryURL.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: fileURL.path) {
                try fileManager.removeItem(at: fileURL)
            }
        }
    }

    private static var mountMethod: DDIMountMethod {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return version.majorVersion > 26 || (version.majorVersion == 26 && version.minorVersion >= 4)
            ? .cryptex
            : .personalized
    }

    private static let mountMethodURL = directoryURL.appendingPathComponent("MountMethod")
    private static let cryptexOnlyFileNames = ["Image.dmg.cryptex_info", "Image.dmg.root_hash"]

    private static var downloadItems: [DDIDownloadItem] {
        switch mountMethod {
        case .cryptex:
            return cryptexDownloadItems
        case .personalized:
            return personalizedDownloadItems
        }
    }

    private static let cryptexDownloadItems: [DDIDownloadItem] = [
        .init(
            name: "Build Manifest",
            fileName: "BuildManifest.plist",
            urlString: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/Xcode_iOS_DDI_Cryptex/BuildManifest.plist"
        ),
        .init(
            name: "Image",
            fileName: "Image.dmg",
            urlString: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/Xcode_iOS_DDI_Cryptex/Image.dmg"
        ),
        .init(
            name: "TrustCache",
            fileName: "Image.dmg.trustcache",
            urlString: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/Xcode_iOS_DDI_Cryptex/Image.dmg.trustcache"
        ),
        .init(
            name: "Cryptex Info",
            fileName: "Image.dmg.cryptex_info",
            urlString: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/Xcode_iOS_DDI_Cryptex/Image.dmg.cryptex_info"
        ),
        .init(
            name: "Root Hash",
            fileName: "Image.dmg.root_hash",
            urlString: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/Xcode_iOS_DDI_Cryptex/Image.dmg.root_hash"
        )
    ]

    private static let personalizedDownloadItems: [DDIDownloadItem] = [
        .init(
            name: "Build Manifest",
            fileName: "BuildManifest.plist",
            urlString: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/Xcode_iOS_DDI_Personalized/BuildManifest.plist"
        ),
        .init(
            name: "Image",
            fileName: "Image.dmg",
            urlString: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/Xcode_iOS_DDI_Personalized/Image.dmg"
        ),
        .init(
            name: "TrustCache",
            fileName: "Image.dmg.trustcache",
            urlString: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/Xcode_iOS_DDI_Personalized/Image.dmg.trustcache"
        )
    ]
}

private enum DDIMountMethod: String {
    case cryptex
    case personalized
}

private struct DDIDownloadItem {
    let name: String
    let fileName: String
    let urlString: String
}

enum DDIDownloadError: LocalizedError {
    case invalidURL(String)
    case invalidResponse
    case badStatus(Int)

    var errorDescription: String? {
        switch self {
        case .invalidURL(let string):
            return "Invalid download URL: \(string)"
        case .invalidResponse:
            return "The DDI server returned an invalid response."
        case .badStatus(let statusCode):
            return "The DDI server returned HTTP \(statusCode)."
        }
    }
}
