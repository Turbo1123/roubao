import Foundation

enum CellularConnectionError: Error, LocalizedError, Equatable {
    case helperRequired, helperDidNotFinish, restoreNotConfirmed
    var errorDescription: String? {
        switch self {
        case .helperRequired: "本机连接未建立。无需连接 Wi‑Fi，请添加蜂窝连接助手，或暂时关闭蜂窝数据后重试。"
        case .helperDidNotFinish: "蜂窝连接助手没有完成。请确认快捷指令已添加，并重试连接。"
        case .restoreNotConfirmed: "本机连接已建立，但未确认蜂窝数据恢复。请在控制中心开启蜂窝数据后继续。"
        }
    }
}
/// The VPN must already be running before invoking this recovery: starting it
/// with every physical interface off can fail. Only the helper toggles cellular.
@MainActor
enum CellularConnectionStrategy {
    static func establish(cellularOnly: Bool, mayRunHelper: Bool,
        connect: () async throws -> Void,
        turnOff: () async -> Bool, turnOn: () async -> Bool) async throws -> Bool {
        do { try await connect(); return false }
        catch {
            if error is CancellationError { throw error }
            // Invalid pairing is not a route failure and won't be fixed by toggling data.
            guard cellularOnly, (error as NSError).code != -9 else { throw error }
        }
        guard mayRunHelper else { throw CellularConnectionError.helperRequired }
        var restoreAttempted = false
        do {
            guard await turnOff() else { throw CellularConnectionError.helperDidNotFinish }
            try Task.checkCancellation()
            try await connect()
            restoreAttempted = true
            guard await turnOn() else { throw CellularConnectionError.restoreNotConfirmed }
            try Task.checkCancellation()
            return true
        } catch {
            // Also restore after a lost callback: the helper may already have disabled data.
            if !restoreAttempted { _ = await turnOn() }
            throw error
        }
    }
}
