//
//  HapticButtonStyle.swift
//  iPhoneUse
//
//  A light tap on every button press. Wraps another style so buttons keep their look;
//  set once at the root, and around any explicit `.buttonStyle(...)`.
//

import SwiftUI
import UIKit

struct HapticButtonStyle<Style: PrimitiveButtonStyle>: PrimitiveButtonStyle {
    let style: Style

    func makeBody(configuration: Configuration) -> some View {
        Button(role: configuration.role) {
            tapHaptic()
            configuration.trigger()
        } label: {
            configuration.label
        }
        .buttonStyle(style)
    }
}

extension PrimitiveButtonStyle {
    static func haptic<S: PrimitiveButtonStyle>(_ style: S) -> HapticButtonStyle<S> where Self == HapticButtonStyle<S> {
        HapticButtonStyle(style: style)
    }
}

/// For tappable things that aren't buttons.
func tapHaptic() {
    UIImpactFeedbackGenerator(style: .light).impactOccurred()
}
