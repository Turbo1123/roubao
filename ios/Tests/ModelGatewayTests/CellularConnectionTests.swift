import XCTest
@testable import RoubaoModel
@MainActor final class CellularConnectionTests: XCTestCase {
    func testCellularDirectSuccessDoesNotToggleData() async throws {
        var events=[String]()
        let result=try await CellularConnectionStrategy.establish(cellularOnly:true,mayRunHelper:false,connect:{events.append("connect")},turnOff:{events.append("off");return true},turnOn:{events.append("on");return true})
        XCTAssertFalse(result);XCTAssertEqual(events,["connect"])
    }
    func testFallbackRestoresDataBeforeCompleting() async throws {
        var events=[String]();var attempt=0
        let result=try await CellularConnectionStrategy.establish(cellularOnly:true,mayRunHelper:true,connect:{attempt+=1;events.append("connect");if attempt==1 {throw URLError(.cannotConnectToHost)}},turnOff:{events.append("off");return true},turnOn:{events.append("on");return true})
        XCTAssertTrue(result);XCTAssertEqual(events,["connect","off","connect","on"])
    }
    func testFailedRecoveryStillRestoresData() async {
        var events=[String]()
        do { _=try await CellularConnectionStrategy.establish(cellularOnly:true,mayRunHelper:true,connect:{events.append("connect");throw URLError(.cannotConnectToHost)},turnOff:{events.append("off");return true},turnOn:{events.append("on");return true});XCTFail() } catch {}
        XCTAssertEqual(events,["connect","off","connect","on"])
    }
    func testLostOffCallbackStillRestoresData() async {
        var restored=0
        do { _=try await CellularConnectionStrategy.establish(cellularOnly:true,mayRunHelper:true,connect:{throw URLError(.cannotConnectToHost)},turnOff:{false},turnOn:{restored+=1;return true});XCTFail() } catch {}
        XCTAssertEqual(restored,1)
    }
    func testInvalidPairingNeverTogglesNetwork() async {
        var toggles=0
        do { _=try await CellularConnectionStrategy.establish(cellularOnly:true,mayRunHelper:true,connect:{throw NSError(domain:"pairing",code:-9)},turnOff:{toggles+=1;return true},turnOn:{toggles+=1;return true});XCTFail() } catch {}
        XCTAssertEqual(toggles,0)
    }
}
extension CellularConnectionTests {
    func testMissingHelperDoesNotChangeNetwork() async {
        var toggles=0
        do { _=try await CellularConnectionStrategy.establish(cellularOnly:true,mayRunHelper:false,connect:{throw URLError(.cannotConnectToHost)},turnOff:{toggles+=1;return true},turnOn:{toggles+=1;return true});XCTFail() } catch {}
        XCTAssertEqual(toggles,0)
    }
    func testUnconfirmedRestoreCannotReportSuccess() async {
        var attempt=0
        do { _=try await CellularConnectionStrategy.establish(cellularOnly:true,mayRunHelper:true,connect:{attempt+=1;if attempt==1 {throw URLError(.cannotConnectToHost)}},turnOff:{true},turnOn:{false});XCTFail() }
        catch { XCTAssertEqual(error as? CellularConnectionError, .restoreNotConfirmed) }
    }
}
