import Foundation

struct PhoneStepStatus: Equatable, Sendable {
    let phase: String
    let detail: String
    static func describe(name: String, arguments: [String: Any], visibleText: [PhoneScreenText] = [], apps: [String: String] = [:]) -> PhoneStepStatus {
        switch name {
        case "set_plan": return .init(phase: "规划", detail: "正在规划接下来的操作")
        case "open_app":
            let app = (arguments["bundle_id"] as? String).flatMap { apps[$0] } ?? "应用"
            return .init(phase: "打开", detail: "正在打开" + app)
        case "ask_user_question": return .init(phase: "等待答复", detail: "需要你的选择，点按返回肉包答复")
        case "confirmed_action": return .init(phase: "等待确认", detail: "操作已暂停，等待你确认")
        case "tap_text":
            let text = visibleText.first { $0.id == arguments["id"] as? String }?.text
            return .init(phase: "点击", detail: text.map { "点击“" + String($0.prefix(28)) + "”" } ?? "正在点击界面上的文字目标")
        case "tap", "tap_target": return .init(phase: "点击", detail: "正在点击界面目标")
        case "type", "replace_text": return .init(phase: "输入", detail: "正在填写内容")
        case "press_key": return .init(phase: "按键", detail: arguments["key"] as? String == "return" ? "正在按回车或搜索键" : "正在操作编辑按键")
        case "swipe", "swipe_up", "swipe_down": return .init(phase: "浏览", detail: "正在查看下一屏")
        case "long_press": return .init(phase: "长按", detail: "正在长按界面目标")
        case "home": return .init(phase: "切换", detail: "正在返回主屏幕")
        case "done": return .init(phase: "完成", detail: "任务已完成")
        case "create_pdf_report": return .init(phase: "报告", detail: "正在生成图文 PDF 报告")
        case "update_task_progress": return .init(phase: "记录", detail: "正在保存已查到的资料与进度")
        case "wait": return .init(phase: "等待", detail: "正在等待界面加载")
        default: return .init(phase: "执行", detail: "正在执行当前步骤")
        }
    }
}
