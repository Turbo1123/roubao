import Foundation
import zlib

enum VolcASRError: LocalizedError {
    case invalidFrame, compression, service(UInt32), empty, timeout
    var errorDescription: String? {
        switch self {
        case .invalidFrame: "语音服务返回了无效数据"
        case .compression: "语音数据压缩失败"
        case .service(let code): "语音服务错误（\(code)），请检查 Key、资源权限与账户状态"
        case .empty: "未识别到语音，请重试"
        case .timeout: "语音识别超时，请检查网络后重试"
        }
    }
}
enum VolcASRCodec {
    static func gzip(_ data: Data, decompress: Bool = false) throws -> Data {
        var stream = z_stream()
        let status = decompress
            ? inflateInit2_(&stream, 15 + 32, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
            : deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard status == Z_OK else { throw VolcASRError.compression }
        defer { if decompress { inflateEnd(&stream) } else { deflateEnd(&stream) } }
        return try data.withUnsafeBytes { source in
            stream.next_in = UnsafeMutablePointer(mutating: source.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(data.count)
            var result = Data()
            var status: Int32 = Z_OK
            repeat {
                var buffer = [UInt8](repeating: 0, count: 16_384)
                status = buffer.withUnsafeMutableBytes { output in
                    stream.next_out = output.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(output.count)
                    return decompress ? inflate(&stream, Z_NO_FLUSH) : deflate(&stream, Z_FINISH)
                }
                guard status == Z_OK || status == Z_STREAM_END else { throw VolcASRError.compression }
                result.append(contentsOf: buffer.prefix(buffer.count - Int(stream.avail_out)))
                guard result.count <= 4_194_304 else { throw VolcASRError.invalidFrame }
            } while status != Z_STREAM_END
            return result
        }
    }
    static func frame(type: UInt8, flags: UInt8 = 0, serialization: UInt8 = 0, payload: Data) throws -> Data {
        let body = try gzip(payload)
        var size = UInt32(body.count).bigEndian
        return Data([0x11, type << 4 | flags, serialization << 4 | 1, 0]) + withUnsafeBytes(of: &size) { Data($0) } + body
    }
    struct Result { let text: String?; let final: Bool }
    static func parse(_ data: Data) throws -> Result {
        let bytes = [UInt8](data)
        guard bytes.count >= 8, bytes[0] >> 4 == 1 else { throw VolcASRError.invalidFrame }
        let type = bytes[1] >> 4, flags = bytes[1] & 15
        var offset = Int(bytes[0] & 15) * 4
        func uint(_ at: Int) throws -> UInt32 {
            guard at >= 4, at + 4 <= bytes.count else { throw VolcASRError.invalidFrame }
            return bytes[at..<at+4].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        }
        if type == 15 { throw VolcASRError.service(try uint(offset)) }
        guard type == 9 else { throw VolcASRError.invalidFrame }
        if flags & 1 != 0 { offset += 4 }
        let size = Int(try uint(offset)); offset += 4
        guard size <= 4_194_304, offset + size <= bytes.count else { throw VolcASRError.invalidFrame }
        var body = Data(bytes[offset..<offset+size])
        if bytes[2] & 15 == 1 { body = try gzip(body, decompress: true) }
        guard let json = try JSONSerialization.jsonObject(with: body) as? [String: Any] else { throw VolcASRError.invalidFrame }
        let result = (json["result"] as? [String: Any]) ?? (json["result"] as? [[String: Any]])?.first
        return Result(text: result?["text"] as? String, final: flags & 2 != 0)
    }
}
enum VolcASR {
    static let endpoint = URL(string: "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel")!
    static let resource = "volc.seedasr.sauc.duration"
    static func transcribe(pcm: Data, apiKey: String, resource: String = resource,
                           onTranscript: @escaping @Sendable (String) async -> Void = { _ in }) async throws -> String {
        guard !pcm.isEmpty, pcm.count % 2 == 0 else { throw VolcASRError.empty }
        let (chunks, continuation) = AsyncStream<Data>.makeStream()
        let producer = Task {
            var offset = 0
            while offset < pcm.count, !Task.isCancelled {
                let end = min(offset + 6400, pcm.count)
                continuation.yield(pcm.subdata(in: offset..<end)); offset = end
                try? await Task.sleep(for: .milliseconds(120))
            }
            continuation.finish()
        }
        defer { producer.cancel(); continuation.finish() }
        return try await recognize(chunks: chunks, apiKey: apiKey, resource: resource, onTranscript: onTranscript)
    }
    static func recognize(chunks: AsyncStream<Data>, apiKey: String, resource: String = resource,
                          onTranscript: @escaping @Sendable (String) async -> Void) async throws -> String {
        var request = URLRequest(url: endpoint)
        request.timeoutInterval = 15
        request.setValue(apiKey, forHTTPHeaderField: "X-Api-Key")
        request.setValue(resource, forHTTPHeaderField: "X-Api-Resource-Id")
        request.setValue(UUID().uuidString, forHTTPHeaderField: "X-Api-Connect-Id")
        let session = URLSession(configuration: .ephemeral)
        let socket = session.webSocketTask(with: request)
        let activity = VolcASRActivity()
        socket.resume()
        defer { socket.cancel(with: .normalClosure, reason: nil); session.invalidateAndCancel() }
        return try await withTaskCancellationHandler {
            try await withThrowingTaskGroup(of: String?.self) { group in
                defer { group.cancelAll(); socket.cancel(with: .normalClosure, reason: nil) }
                group.addTask {
                    let initBody: [String: Any] = ["user": ["uid": "roubao"], "audio": ["format": "pcm", "rate": 16000, "bits": 16, "channel": 1], "request": ["model_name": "bigmodel", "enable_itn": true, "enable_punc": true]]
                    try await socket.send(.data(VolcASRCodec.frame(type: 1, serialization: 1, payload: JSONSerialization.data(withJSONObject: initBody))))
                    var pending = Data()
                    for await chunk in chunks {
                        try Task.checkCancellation()
                        guard chunk.count % 2 == 0 else { throw VolcASRError.invalidFrame }
                        pending.append(chunk)
                        while pending.count >= 6400 {
                            try await socket.send(.data(VolcASRCodec.frame(type: 2, payload: Data(pending.prefix(6400)))))
                            pending.removeFirst(6400)
                            await activity.touch()
                        }
                    }
                    try Task.checkCancellation()
                    try await socket.send(.data(VolcASRCodec.frame(type: 2, flags: 2, payload: pending)))
                    await activity.touch()
                    return nil
                }
                group.addTask {
                    var latest = ""
                    while true {
                        try Task.checkCancellation()
                        let message = try await socket.receive()
                        await activity.touch()
                        guard case .data(let data) = message else { throw VolcASRError.invalidFrame }
                        let result = try VolcASRCodec.parse(data)
                        if let text = result.text, text != latest {
                            latest = text
                            await onTranscript(text)
                        }
                        if result.final {
                            guard !latest.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw VolcASRError.empty }
                            return latest
                        }
                    }
                }
                group.addTask {
                    while true {
                        try await Task.sleep(for: .seconds(5))
                        if await activity.idleSeconds() > 40 { throw VolcASRError.timeout }
                    }
                }
                for try await result in group { if let result { return result } }
                throw VolcASRError.empty
            }
        } onCancel: { socket.cancel(with: .goingAway, reason: nil) }
    }
}
private actor VolcASRActivity {
    private var last = ProcessInfo.processInfo.systemUptime
    func touch() { last = ProcessInfo.processInfo.systemUptime }
    func idleSeconds() -> Double { ProcessInfo.processInfo.systemUptime - last }
}
