//
//  App.swift
//  iPhoneUse
//

import SwiftUI

@main
struct IPhoneUseApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var wasInBackground = false

    init() {
        UserDefaults.standard.register(defaults: ["keepAliveAudio": true, "keepAliveLocation": true])
        QuestionNotifications.shared.configure()
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if UserDefaults.standard.bool(forKey: "pasteProbe") { PastePermissionProbeView() }
                else { AgentView() } // Settings opens from its toolbar
            }
            .buttonStyle(.haptic(.automatic))
            .onChange(of: scenePhase) { _, phase in
                switch phase {
                case .background:
                    wasInBackground = true
                case .active where wasInBackground:
                    // The tunnel often dies while suspended. Don't swap it under a running agent.
                    wasInBackground = false
                    if !PhoneAgent.shared.isRunning {
                        Task { await Connection.shared.connect(force: true) }
                    }
                default:
                    break
                }
            }
        }
    }
}
