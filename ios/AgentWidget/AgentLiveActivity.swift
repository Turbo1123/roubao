import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct AgentWidgetBundle: WidgetBundle {
    var body: some Widget {
        AgentLiveActivity()
    }
}

struct AgentLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AgentActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Label("肉包", systemImage: statusIcon(context.state))
                        .font(.caption.bold())
                        .foregroundStyle(.secondary)
                    Spacer()
                    StatusText(context: context)
                        .font(.caption.bold())
                    StopButton(context: context)
                }
                Text(context.attributes.prompt)
                    .font(.headline)
                    .lineLimit(2)
                Text(context.state.line)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            .padding()
            .widgetURL(URL(string: "iphoneuse://task"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("肉包", systemImage: statusIcon(context.state))
                        .font(.headline)
                        .foregroundStyle(isWaiting(context.state) ? .orange : .blue)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    HStack {
                        StatusText(context: context)
                            .font(.headline)
                        StopButton(context: context)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(context.attributes.prompt)
                            .font(.subheadline.bold())
                            .lineLimit(1)
                        HStack(alignment: .top) {
                            Text(context.state.line).font(.subheadline).lineLimit(2)
                            Spacer(minLength: 4)
                            if isWaiting(context.state) {
                                Link("去答复", destination: URL(string: "iphoneuse://task")!).font(.subheadline.bold()).foregroundStyle(.orange)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                Image(systemName: statusIcon(context.state)).foregroundStyle(isWaiting(context.state) ? .orange : .blue)
            } compactTrailing: {
                Text(!context.state.running ? "✓" : isWaiting(context.state) ? "待答复" : "\(context.state.phase ?? "执行") \(context.state.step)")
                    .font(.caption.bold())
                    .monospacedDigit()
                    .foregroundStyle(isWaiting(context.state) ? .orange : .primary)
            } minimal: {
                Image(systemName: statusIcon(context.state)).foregroundStyle(isWaiting(context.state) ? .orange : .blue)
            }
            .widgetURL(URL(string: "iphoneuse://task"))
        }
    }
}

private struct StatusText: View {
    let context: ActivityViewContext<AgentActivityAttributes>

    var body: some View {
        if context.state.running {
            Text(isWaiting(context.state) ? "等待答复" : "第 \(context.state.step) 步")
                .monospacedDigit()
        } else {
            Text("已结束")
        }
    }
}

private struct StopButton: View {
    let context: ActivityViewContext<AgentActivityAttributes>

    var body: some View {
        if context.state.running {
            Button(intent: StopAgentIntent()) {
                Image(systemName: "stop.fill")
            }
            .tint(.red)
            .accessibilityLabel("停止任务")
        }
    }
}

private func isWaiting(_ state: AgentActivityAttributes.ContentState) -> Bool {
    state.phase == "等待答复" || state.phase == "等待确认"
}
private func statusIcon(_ state: AgentActivityAttributes.ContentState) -> String {
    !state.running ? "checkmark.circle.fill" : isWaiting(state) ? "questionmark.bubble.fill" : "sparkles"
}
