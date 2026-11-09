import SwiftUI
import QuickLook

struct TaskFilesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var files: [URL] = []
    @State private var shots: [URL] = []
    @State private var preview: URL?

    private func load() {
        func list(_ directory: URL) -> [URL] {
            let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey], options: [.skipsHiddenFiles])) ?? []
            return urls.filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
                .sorted { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) > ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
        }
        files = list(TaskReportRenderer.directory)
        shots = list(ChatEntry.shotsURL)
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    if files.isEmpty { Text("生成的 PDF 和其他报告会显示在这里").foregroundStyle(.secondary) }
                    ForEach(files, id: \.self) { row($0, screenshot: false) }
                } header: { Text("报告与文件") } footer: {
                    Text("点按预览；使用系统分享面板可存储到文件、隔空投送或选择其他 App。")
                }
                Section("任务截图") {
                    ForEach(shots, id: \.self) { row($0, screenshot: true) }
                }
            }
            .navigationTitle("任务文件")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("完成") { dismiss() } }
                if !files.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) { ShareLink(items: files) { Label("分享报告", systemImage: "square.and.arrow.up") } }
                }
            }
            .onAppear(perform: load)
            .refreshable { load() }
            .sheet(isPresented: Binding(get: { preview != nil }, set: { if !$0 { preview = nil } })) {
                if let url = preview { TaskFilePreview(url: url) }
            }
        }
    }
    @ViewBuilder private func row(_ url: URL, screenshot: Bool) -> some View {
        HStack(spacing: 12) {
            Button {
                preview = url
            } label: {
                HStack(spacing: 10) {
                    if screenshot, let image = UIImage(contentsOfFile: url.path) {
                        Image(uiImage: image).resizable().scaledToFit().frame(width: 38, height: 52).clipShape(RoundedRectangle(cornerRadius: 5))
                    } else { Image(systemName: url.pathExtension.lowercased() == "pdf" ? "doc.richtext" : "doc").font(.title2).foregroundStyle(.blue) }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(screenshot ? "任务截图" : url.lastPathComponent).lineLimit(2)
                        if let date = try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate {
                            Text(date.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }.buttonStyle(.borderless)
        }.padding(.vertical, 3)
    }
}

struct TaskFilePreview: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL
    var body: some View {
        NavigationStack {
            NativeFilePreview(url: url)
                .navigationTitle("文件预览")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar(.visible, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("完成") { dismiss() }.accessibilityLabel("关闭文件预览")
                    }
                    ToolbarItem(placement: .topBarLeading) {
                        ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }.accessibilityLabel("分享文件")
                    }
                }
        }.presentationDragIndicator(.visible)
    }
}
private struct NativeFilePreview: UIViewControllerRepresentable {
    let url: URL
    func makeCoordinator() -> Coordinator { Coordinator(url: url) }
    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController(); controller.dataSource = context.coordinator
        return controller
    }
    func updateUIViewController(_ uiViewController: QLPreviewController, context: Context) {}
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}
