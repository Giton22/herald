// swift-tools-version:5.9
// The WebRTC build Android's webrtc-android is (libs.versions.toml `webrtc`): webrtc-sdk's prebuilt framework
// for that release. webrtc-sdk/Specs' own manifest names visionOS 26 under tools 5.9, which no Xcode resolves,
// so the app takes the same download through this one. A new release: its URL and the checksum from its
// Package.swift.
import PackageDescription

let package = Package(
    name: "WebRTC",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "WebRTC", targets: ["WebRTC"]),
    ],
    targets: [
        .binaryTarget(
            name: "WebRTC",
            url: "https://github.com/webrtc-sdk/Specs/releases/download/150.7871.01/WebRTC.xcframework.zip",
            checksum: "03815cdf2f6a0ed328c94d74cce8fd1b8d2b6e95e2b37eab66795012fcecfdfa"
        ),
    ]
)
