import AppIntents

// Stop button on the Live Activity. Compiled into the widget too (so the button can
// reference it), but because the app also contains it, iOS runs it in the app.
struct StopAgentIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Stop Agent"

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET
        PhoneAgent.shared.stop()
        #endif
        return .result()
    }
}
