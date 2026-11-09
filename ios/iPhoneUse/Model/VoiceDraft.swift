import Foundation

/// ASR texts are cumulative revisions. Preserve manual edits instead of
/// overwriting them when a delayed final result arrives.
struct VoiceDraft {
    private var base = ""
    private var last = ""
    private var manuallyEdited = false
    mutating func begin(_ current: String) { base = current; last = current; manuallyEdited = false }
    mutating func update(_ transcript: String, current: String) -> String {
        guard !transcript.isEmpty else { return current }
        if current != last { manuallyEdited = true }
        guard !manuallyEdited else { return current }
        last = base + (base.isEmpty || base.hasSuffix(" ") || base.hasSuffix("\n") ? "" : " ") + transcript
        return last
    }
}
