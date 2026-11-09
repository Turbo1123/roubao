//
//  PacketTunnelProvider.swift
//  TunnelProvider
//
//  Based on StosVPN / LocalDevVPN by the SideStore Team and Stossy11
//  (https://github.com/jkcoxson/LocalDevVPN, StosVPN License).
//
//  A loopback "VPN": packets the app sends to 10.7.0.1 come back with source
//  and destination swapped, so this iPhone's own developer services see them
//  as coming from a paired peer.
//

import NetworkExtension

final class PacketTunnelProvider: NEPacketTunnelProvider {
    static let interfaceIP = "10.7.1.1"
    static let peerIP = "10.7.0.1"

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let ipv4 = NEIPv4Settings(addresses: [Self.interfaceIP], subnetMasks: ["255.255.255.255"])
        ipv4.includedRoutes = [NEIPv4Route(destinationAddress: Self.peerIP, subnetMask: "255.255.255.255")]
        ipv4.excludedRoutes = [.default()]

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: Self.peerIP)
        settings.ipv4Settings = ipv4
        setTunnelNetworkSettings(settings) { error in
            if error == nil { self.loopPackets() }
            completionHandler(error)
        }
    }

    private func loopPackets() {
        packetFlow.readPackets { [self] packets, protocols in
            var swapped = packets
            // IPv4 source (bytes 12-15) <-> destination (16-19). Checksums stay valid.
            for i in swapped.indices where protocols[i].int32Value == AF_INET && swapped[i].count >= 20 {
                swapped[i].withUnsafeMutableBytes { bytes in
                    guard let words = bytes.baseAddress?.assumingMemoryBound(to: UInt32.self) else { return }
                    (words[3], words[4]) = (words[4], words[3])
                }
            }
            packetFlow.writePackets(swapped, withProtocols: protocols)
            loopPackets()
        }
    }
}
