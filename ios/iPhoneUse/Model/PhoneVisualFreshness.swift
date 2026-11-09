import Foundation
import CoreGraphics
import ImageIO

/// Check the destination and its surrounding context, rather than rejecting a
/// stable button because an unrelated banner/video changed elsewhere.
enum PhoneVisualFreshness {
    static func matches(region: CGRect, before: PhoneFrame, current: PhoneFrame) -> Bool {
        guard PhoneStallMonitor.Snapshot(before).samePage(as: PhoneStallMonitor.Snapshot(current)),
              region.width > 0, region.height > 0 else { return false }
        let expanded = region.insetBy(dx: -max(0.02, region.width * 0.2), dy: -max(0.015, region.height * 0.2)).intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
        func words(_ frame: PhoneFrame) -> Set<String> {
            Set(frame.text.filter { expanded.contains(CGPoint(x: ($0.x + $0.width / 2) / frame.width, y: ($0.y + $0.height / 2) / frame.height)) }.map { $0.text.split(whereSeparator: \.isWhitespace).joined() })
        }
        let a = words(before), b = words(current), union = a.union(b)
        guard union.isEmpty || Double(a.intersection(b).count) / Double(union.count) >= 0.85 else { return false }
        func pixels(_ frame: PhoneFrame) -> [UInt8]? {
            guard let src = CGImageSourceCreateWithData(frame.data as CFData, nil), let image = CGImageSourceCreateImageAtIndex(src, 0, nil),
                  let crop = image.cropping(to: CGRect(x: expanded.minX * Double(image.width), y: expanded.minY * Double(image.height), width: expanded.width * Double(image.width), height: expanded.height * Double(image.height))) else { return nil }
            var samples = [UInt8](repeating: 0, count: 32 * 32)
            let ok = samples.withUnsafeMutableBytes { bytes -> Bool in
                guard let ctx = CGContext(data: bytes.baseAddress, width: 32, height: 32, bitsPerComponent: 8, bytesPerRow: 32, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: 0) else { return false }
                ctx.draw(crop, in: CGRect(x: 0, y: 0, width: 32, height: 32)); return true
            }
            return ok ? samples : nil
        }
        guard let x = pixels(before), let y = pixels(current) else { return before.data == current.data }
        return zip(x, y).reduce(0.0) { $0 + abs(Double($1.0) - Double($1.1)) } / Double(x.count) <= 5
    }
}
