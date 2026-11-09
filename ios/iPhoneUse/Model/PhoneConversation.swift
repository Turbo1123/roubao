import Foundation
import AgentRunKit

/// Preserve resolved tool receipts. A terminal completion call has no tool result;
/// carry its summary into the next turn rather than replaying that call.
enum PhoneConversation {
    static func history(from checkpoint: AgentCheckpoint) -> [ChatMessage] {
        var resolved: [ChatMessage] = []
        var batch: [ChatMessage] = []
        var pending: [String] = []
        for message in checkpoint.messages {
            if case .system = message { continue }
            if pending.isEmpty {
                if case .assistant(let assistant) = message, !assistant.toolCalls.isEmpty {
                    batch = [message]; pending = assistant.toolCalls.map(\.id)
                } else { resolved.append(message) }
            } else if case .tool(let id, _, _) = message, pending.first == id {
                batch.append(message); pending.removeFirst()
                if pending.isEmpty { resolved.append(contentsOf: batch); batch = [] }
            } else { break }
        }
        if let summary = checkpoint.terminalOutcome?.content, !summary.isEmpty {
            resolved.append(.assistant(.init(content: summary)))
        }
        return resolved
    }
}
