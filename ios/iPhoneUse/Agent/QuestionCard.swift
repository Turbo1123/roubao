import SwiftUI

struct QuestionCard: View {
    let question: PhoneQuestion
    let notice: String?
    let submit: (String) -> Void
    let cancel: () -> Void
    @State private var answers: [String: String]
    @State private var step: Int
    @FocusState private var editing: Bool

    init(question: PhoneQuestion, notice: String?, submit: @escaping (String) -> Void, cancel: @escaping () -> Void) {
        self.question = question; self.notice = notice; self.submit = submit; self.cancel = cancel
        let saved = PhoneQuestionStore.live.load()
        let same = saved?.question.id == question.id
        _answers = State(initialValue: same ? saved?.draftAnswers ?? [:] : [:])
        _step = State(initialValue: same ? min(question.items.count, max(0, saved?.draftStep ?? 0)) : 0)
    }
    private var reviewing: Bool { step == question.items.count }
    private var complete: Bool {
        question.items.allSatisfy { !(answers[$0.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
    private func next() { editing = false; step = min(step + 1, question.items.count) }
    private func saveDraft() {
        guard var saved = PhoneQuestionStore.live.load(), saved.question.id == question.id, saved.answer == nil else { return }
        saved.draftAnswers = answers; saved.draftStep = step
        try? PhoneQuestionStore.live.save(saved)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("肉包需要你的答复", systemImage: "questionmark.bubble.fill").font(.headline)
                Spacer()
                Text(reviewing ? "确认答复" : "\(step + 1) / \(question.items.count)").font(.caption).foregroundStyle(.secondary)
            }
            ProgressView(value: Double(step), total: Double(question.items.count))
            Text("任务已暂停，最后确认提交后继续").font(.caption).foregroundStyle(.secondary)
            if reviewing {
                ForEach(Array(question.items.enumerated()), id: \.element.id) { index, item in
                    VStack(alignment: .leading, spacing: 5) {
                        HStack(alignment: .top) {
                            Text(item.prompt).font(.subheadline.bold()).lineLimit(3)
                            Spacer()
                            Button("修改") { step = index }.font(.caption)
                        }
                        Text(answers[item.id] ?? "未填写").font(.subheadline).foregroundStyle(.secondary).lineLimit(4)
                    }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                }
            } else {
                let item = question.items[step]
                Text(item.prompt).font(.subheadline.bold()).textSelection(.enabled)
                ForEach(item.options ?? [], id: \.label) { option in
                    Button {
                        answers[item.id] = option.label
                        next()
                    } label: {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: answers[item.id] == option.label ? "checkmark.circle.fill" : "circle")
                            VStack(alignment: .leading, spacing: 3) {
                                Text(option.label)
                                if let detail = option.description { Text(detail).font(.caption).foregroundStyle(.secondary) }
                            }
                            Spacer(minLength: 0)
                        }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
                            .background(answers[item.id] == option.label ? Color.accentColor.opacity(0.12) : Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                    }.buttonStyle(.plain)
                }
                TextField(item.options?.isEmpty == false ? "或自行填写，再点下一步" : "填写你的答复", text: Binding(
                    get: { answers[item.id] ?? "" }, set: { answers[item.id] = $0 }), axis: .vertical)
                    .textFieldStyle(.roundedBorder).lineLimit(1...4).focused($editing).submitLabel(.next)
                    .onSubmit { if !(answers[item.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { next() } }
            }
            if let notice {
                HStack(alignment: .top) {
                    Text(notice).font(.caption).foregroundStyle(.orange)
                    Button("通知设置") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }.font(.caption)
                }
            }
            HStack {
                if step > 0 { Button("上一步") { editing = false; step -= 1 } }
                Button("停止任务", role: .destructive, action: cancel).font(.subheadline)
                Spacer()
                if reviewing {
                    Button("确认并继续") { if let response = try? question.response(answers) { submit(response) } }
                        .buttonStyle(.borderedProminent).disabled(!complete)
                } else {
                    Button(step == question.items.count - 1 ? "检查答复" : "下一步", action: next)
                        .buttonStyle(.borderedProminent)
                        .disabled((answers[question.items[step].id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }.padding(14).background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20))
            .onChange(of: answers) { _, _ in saveDraft() }
            .onChange(of: step) { _, _ in saveDraft() }
    }
}
