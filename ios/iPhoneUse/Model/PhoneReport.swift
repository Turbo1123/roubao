import Foundation

struct PhoneReport: Codable, Sendable {
    struct Item: Codable, Sendable {
        let platform: String
        let product: String
        let specification: String
        let displayed_price: String
        let price: Double?
        let comparable: Bool?
        let seller: String?
        let conditions: String
        let evidence_id: String
        let evidence_step: Int?
    }
    let title: String
    let summary: String
    let items: [Item]
    let notes: String?
    static func decode(_ data: Data) throws -> PhoneReport {
        struct Args: Decodable { let report_json: String }
        let args = try JSONDecoder().decode(Args.self, from: data)
        guard args.report_json.utf8.count <= 100_000 else { throw PhoneHarnessError.invalidArguments }
        let report = try JSONDecoder().decode(Self.self, from: Data(args.report_json.utf8))
        guard !report.title.isEmpty, report.title.count <= 120, !report.summary.isEmpty, report.summary.count <= 5000,
              (1...30).contains(report.items.count), (report.notes?.count ?? 0) <= 5000,
              report.items.allSatisfy({ row in
                  !row.platform.isEmpty && row.platform.count <= 100 && !row.product.isEmpty && row.product.count <= 500
                  && row.specification.count <= 500 && !row.displayed_price.isEmpty && row.displayed_price.count <= 150
                  && (row.seller?.count ?? 0) <= 300 && row.conditions.count <= 1500
                  && UUID(uuidString: row.evidence_id) != nil
                  && (row.evidence_step == nil || (1...100_000).contains(row.evidence_step!))
                  && (row.price == nil || (row.price!.isFinite && (0...10_000_000).contains(row.price!)))
              }) else { throw PhoneHarnessError.invalidArguments }
        return report
    }
}
