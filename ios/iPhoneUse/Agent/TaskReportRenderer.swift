import UIKit

enum TaskReportRenderer {
    struct Result { let url: URL; let pages: Int }
    static let directory = URL.documentsDirectory.appendingPathComponent("Reports", isDirectory: true)

    static func create(_ report: PhoneReport, evidence: [String: URL]) throws -> Result {
        guard report.items.allSatisfy({ evidence[$0.evidence_id.uppercased()].flatMap { UIImage(contentsOfFile: $0.path) } != nil }) else {
            throw NSError(domain: "report", code: 1, userInfo: [NSLocalizedDescriptionKey: "报告引用了缺失的截图步骤，请使用本任务已有的截图证据。"])
        }
        let width: CGFloat = 595, height: CGFloat = 842, margin: CGFloat = 36
        let blue = UIColor(red: 0.12, green: 0.28, blue: 0.61, alpha: 1)
        let muted = UIColor(white: 0.38, alpha: 1)
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [kCGPDFContextTitle as String: report.title, kCGPDFContextAuthor as String: "肉包"]
        var pages = 0
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: width, height: height), format: format).pdfData { context in
            var y = margin
            func draw(_ text: String, rect: CGRect, size: CGFloat = 11, bold: Bool = false, color: UIColor = .black) {
                let p = NSMutableParagraphStyle(); p.lineSpacing = 4
                (text as NSString).draw(in: rect, withAttributes: [.font: bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size), .foregroundColor: color, .paragraphStyle: p])
            }
            func page() {
                context.beginPage(); pages += 1; y = margin
                UIColor.white.setFill(); context.cgContext.fill(CGRect(x: 0, y: 0, width: width, height: height))
                draw("肉包 · 实机比价报告", rect: CGRect(x: margin, y: 15, width: 420, height: 15), size: 8, color: muted)
                draw("\(pages)", rect: CGRect(x: width - 60, y: height - 24, width: 25, height: 14), size: 8, color: muted)
                draw(Date().formatted(date: .numeric, time: .shortened), rect: CGRect(x: margin, y: height - 24, width: 420, height: 14), size: 8, color: muted)
            }
            func text(_ string: String, size: CGFloat = 11, bold: Bool = false, color: UIColor = .black) {
                let font = bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
                let p = NSMutableParagraphStyle(); p.lineSpacing = 4
                let box = (string as NSString).boundingRect(with: CGSize(width: width - 2 * margin, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font, .paragraphStyle: p], context: nil)
                let h = ceil(box.height) + 4
                if h > height - 2 * margin - 42, string.count > 1 {
                    let midpoint = string.index(string.startIndex, offsetBy: string.count / 2)
                    text(String(string[..<midpoint]), size: size, bold: bold, color: color)
                    text(String(string[midpoint...]), size: size, bold: bold, color: color)
                    return
                }
                if y + h > height - 42 { page() }
                draw(string, rect: CGRect(x: margin, y: y, width: width - 2 * margin, height: h), size: size, bold: bold, color: color)
                y += h + 8
            }
            page()
            text(report.title, size: 25, bold: true, color: blue)
            text("基于当前账号可见页面 · 未下单或支付", size: 10, color: muted)
            text(report.summary, size: 12)
            let comparable = report.items.filter { $0.comparable == true && $0.price != nil }
            if !comparable.isEmpty {
                text("同规格可比价格", size: 15, bold: true, color: blue)
                let maximum = comparable.compactMap(\.price).max() ?? 1
                for row in comparable {
                    if y + 32 > height - 42 { page() }
                    draw(row.platform, rect: CGRect(x: margin, y: y, width: 90, height: 23), size: 10)
                    blue.withAlphaComponent(0.8).setFill()
                    context.cgContext.fill(CGRect(x: margin + 95, y: y + 1, width: CGFloat((row.price ?? 0) / max(1, maximum)) * 280, height: 14))
                    draw(String(format: "¥%.2f", row.price ?? 0), rect: CGRect(x: width - margin - 95, y: y - 1, width: 95, height: 22), size: 10, bold: true)
                    y += 29
                }
                y += 8
            }
            text("平台对比", size: 15, bold: true, color: blue)
            func tableHeader() {
                blue.setFill(); context.cgContext.fill(CGRect(x: margin, y: y, width: width - 2 * margin, height: 25))
                draw("平台", rect: CGRect(x: margin + 8, y: y + 5, width: 95, height: 18), size: 10, bold: true, color: .white)
                draw("页面价格", rect: CGRect(x: margin + 115, y: y + 5, width: 140, height: 18), size: 10, bold: true, color: .white)
                draw("规格 / 口径", rect: CGRect(x: margin + 270, y: y + 5, width: 245, height: 18), size: 10, bold: true, color: .white)
                y += 25
            }
            if y + 80 > height - 42 { page() }
            tableHeader()
            for (index, row) in report.items.enumerated() {
                if y + 58 > height - 42 { page(); tableHeader() }
                UIColor(white: index.isMultiple(of: 2) ? 0.96 : 1, alpha: 1).setFill()
                context.cgContext.fill(CGRect(x: margin, y: y, width: width - 2 * margin, height: 58))
                draw(String(row.platform.prefix(20)), rect: CGRect(x: margin + 8, y: y + 8, width: 95, height: 45), size: 10)
                draw(String(row.displayed_price.prefix(40)), rect: CGRect(x: margin + 115, y: y + 8, width: 140, height: 45), size: 10, bold: true, color: blue)
                draw(String(row.specification.prefix(60)), rect: CGRect(x: margin + 270, y: y + 8, width: 245, height: 45), size: 9)
                y += 58
            }
            y += 15
            text("商品与优惠条件", size: 15, bold: true, color: blue)
            for (index, row) in report.items.enumerated() {
                if y + 135 > height - 42 { page() }
                text("\(index + 1). \(row.platform)  ·  \(row.displayed_price)", size: 13, bold: true, color: blue)
                text(row.product + "\n" + row.specification + (row.seller.map { "\n店铺：" + $0 } ?? ""), size: 10)
                text("价格条件：" + row.conditions + "\n对应页面证据 \(index + 1)" + (row.comparable == true ? " · 纳入同规格对比" : " · 未纳入同规格最低价排序"), size: 9, color: muted)
            }
            if let notes = report.notes, !notes.isEmpty { text("口径与说明", size: 15, bold: true, color: blue); text(notes, size: 10) }
            text("本报告记录采集时的页面价格和条件。账号优惠、库存和价格可能变化，搜索页价格不等于结算确认价。", size: 9, color: muted)
            for (index, row) in report.items.enumerated() {
                page()
                text("页面证据 \(index + 1) · \(row.platform)", size: 18, bold: true, color: blue)
                text(row.displayed_price + " · " + row.specification, size: 11)
                if let url = evidence[row.evidence_id.uppercased()], let image = UIImage(contentsOfFile: url.path) {
                    let available = CGSize(width: width - 2 * margin, height: height - y - 76)
                    let scale = min(available.width / image.size.width, available.height / image.size.height)
                    let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
                    image.draw(in: CGRect(x: (width - size.width) / 2, y: y, width: size.width, height: size.height))
                    y += size.height + 10
                    text("原始手机页面截图 · " + row.evidence_id, size: 8, color: muted)
                }
            }
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let safe = String(report.title.filter { $0.isLetter || $0.isNumber || "-_ ".contains($0) }.prefix(60))
        let url = directory.appendingPathComponent("\(safe.isEmpty ? "比价报告" : safe)-\(UUID().uuidString.prefix(8)).pdf")
        try data.write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        return .init(url: url, pages: pages)
    }
}
