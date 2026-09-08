// swift-tools-version:6.0
// RePaperKit — the OpenDisplay protocol core for iOS (and macOS, where its tests run).
// The same golden fixtures as go/core pin it byte-for-byte to py-opendisplay.
import PackageDescription

let package = Package(
    name: "RePaperKit",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [.library(name: "RePaperKit", targets: ["RePaperKit"])],
    targets: [
        .target(name: "RePaperKit"),
        .testTarget(name: "RePaperKitTests", dependencies: ["RePaperKit"],
                    resources: [.copy("golden")]),
    ]
)
