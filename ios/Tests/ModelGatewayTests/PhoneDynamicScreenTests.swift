import XCTest
import CoreGraphics
import ImageIO
@testable import RoubaoModel

final class PhoneDynamicScreenTests: XCTestCase {
    func frame(banner: UInt8, target: UInt8 = 140, changeProducts: Bool = false) -> PhoneFrame {
        var pixels = [UInt8](repeating: 230, count: 32*64)
        for y in 0..<18 { for x in 0..<32 { pixels[y*32+x] = banner } }
        for y in 45..<51 { for x in 22..<28 { pixels[y*32+x] = target } }
        if changeProducts { for y in 25..<64 { for x in 0..<32 { pixels[y*32+x] = 30 } } }
        let provider = CGDataProvider(data: Data(pixels) as CFData)!
        let cg = CGImage(width: 32, height: 64, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: 32, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: 0), provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
        let data = NSMutableData(); let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, cg, nil); CGImageDestinationFinalize(destination)
        var text: [PhoneScreenText] = []
        for i in 0..<18 {
            let x = Double((i % 3) * 150)
            let y = 300.0 + Double(i / 3) * 130.0
            text.append(.init(id: "t\(i)", text: "静态控件\(i)", x: x, y: y, width: 80, height: 20, confidence: 1))
        }
        return .init(data: data as Data, width: 640, height: 1387, text: text)
    }
    func testAnimatedBannerCannotResetTwoFailureDetection() {
        let a=frame(banner: 0),b=frame(banner: 255),c=frame(banner: 80)
        XCTAssertFalse(PhoneStallMonitor.Snapshot(a).resembles(PhoneStallMonitor.Snapshot(b)))
        XCTAssertTrue(PhoneStallMonitor.Snapshot(a).samePage(as: PhoneStallMonitor.Snapshot(b)))
        var monitor=PhoneStallMonitor();_=monitor.observe(a)
        for f in [b,c] { monitor.record("tap_text", arguments: Data(#"{"id":"t9"}"#.utf8));_=monitor.observe(f) }
        XCTAssertTrue(monitor.requiresMultimodal)
    }
    func testLargeProductChangeIsProgressDespiteSharedNavigationLabels() {
        XCTAssertFalse(PhoneStallMonitor.Snapshot(frame(banner: 0)).samePage(as: PhoneStallMonitor.Snapshot(frame(banner: 0, changeProducts: true))))
    }
    func testStableTargetSurvivesUnrelatedAnimationButChangedTargetExpires() {
        let region=CGRect(x: 22.0/32, y: 45.0/64, width: 6.0/32, height: 6.0/64)
        XCTAssertTrue(PhoneVisualFreshness.matches(region: region, before: frame(banner: 0), current: frame(banner: 255)))
        XCTAssertFalse(PhoneVisualFreshness.matches(region: region, before: frame(banner: 0), current: frame(banner: 255, target: 0)))
    }
    func testNonretryableJevFailureStaysVisibleWhilePrimaryImagePathRemainsAvailable() async {
        let state=PhoneHarnessState()
        await state.jevFailure(JevError.http(451))
        let feedback=await state.context();XCTAssertTrue(feedback.contains("JEV HTTP 451"));XCTAssertTrue(feedback.contains("locate_visual_target"))
        _=await state.observed(frame(banner: 0));_=await state.observed(frame(banner: 255))
        let unavailable=await state.unavailableJev();XCTAssertNotNil(unavailable)
    }
}
