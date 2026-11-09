// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "RoubaoModel", platforms: [.macOS(.v15)],
 dependencies: [.package(url: "https://github.com/jamesrochabrun/SwiftOpenAI", revision: "3beeb77210b98aaf15a91c37f39c91ea7051abff"), .package(url: "https://github.com/Tom-Ryder/AgentRunKit", revision: "a7ac823ca42d4ecff386a83c10a44a97bd391cdb")],
 targets: [.target(name: "RoubaoModel", dependencies: [.product(name: "SwiftOpenAI", package: "SwiftOpenAI"), .product(name: "AgentRunKit", package: "AgentRunKit")], path: "iPhoneUse/Model"),
 .testTarget(name: "ModelGatewayTests", dependencies: ["RoubaoModel"], path: "Tests/ModelGatewayTests")], swiftLanguageModes: [.v5])
