//
//  ShortcutsGuide.swift
//  iPhoneUse
//
//  How to start runs from outside the app: the "Run Agent" App Intent (Shortcuts,
//  Siri, Action button) and the iphoneuse://run?prompt=... deep link.
//

import SwiftUI
import UIKit

enum DeepLink {
    static let example = "iphoneuse://run?prompt=Turn%20on%20dark%20mode"

    /// The prompt of an `iphoneuse://run?prompt=...` link, nil for anything else.
    static func prompt(from url: URL) -> String? {
        guard url.scheme == "iphoneuse", url.host() == "run",
              let prompt = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                  .queryItems?.first(where: { $0.name == "prompt" })?.value?
                  .trimmingCharacters(in: .whitespacesAndNewlines),
              !prompt.isEmpty
        else { return nil }
        return prompt
    }
}

struct ShortcutsGuideView: View {
    var body: some View {
        Form {
            Section {
                Step(1, "Open the Shortcuts app and tap + to make a new shortcut.")
                Step(2, "Add the \u{201C}Run Agent\u{201D} action from iPhone Use.")
                Step(3, "Type the prompt, or leave it empty to be asked each time.")
                Step(4, "Run it from Siri, the Action button, Back Tap, a widget or an automation.")
                Link(destination: URL(string: "shortcuts://")!) {
                    Label("Open Shortcuts", systemImage: "arrow.up.forward.app")
                }
            } header: {
                Text("Shortcut")
            } footer: {
                Text("Without any setup, you can also say \u{201C}Ask iPhone Use\u{201D} to Siri.")
            }

            Section {
                Text(DeepLink.example)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                Button("Copy Example", systemImage: "doc.on.doc") { UIPasteboard.general.string = DeepLink.example }
            } header: {
                Text("Deep link")
            } footer: {
                Text("Opening this link starts a new session with the prompt. URL-encode the prompt; in Shortcuts, use the \u{201C}Open URLs\u{201D} action.")
            }
        }
        .navigationTitle("快捷指令与链接")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct Step: View {
    let number: Int
    let text: String

    init(_ number: Int, _ text: String) {
        self.number = number
        self.text = text
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(number)")
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(.secondary)
            Text(text)
        }
    }
}
