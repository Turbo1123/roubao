import ActivityKit
import Foundation

// Shared by the app (starts/updates the activity) and AgentWidget (renders it).
struct AgentActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var step: Int
        var line: String
        var running: Bool
        var phase: String? = nil
    }

    var prompt: String
    var startedAt: Date
}
