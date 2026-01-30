# Media Delivery Analysis Tool (Academic Research)

This Android application is designed to study how video media is delivered inside Progressive Web Apps (PWAs). It uses a combination of a local VPN service and a hidden WebView to analyze traffic patterns and detect media streams.

## Core Components

- **UI (`ui/MainActivity.kt`)**: URL input, analysis control, real-time logging, and download confirmation.
- **WebView Module**: Intercepts requests triggered by page loading to identify media endpoints.
- **Foreground Service (`service/AnalyzerForegroundService.kt`)**: Ensures persistent analysis according to Android background limits.
- **VPN Layer (`vpn/TrafficVpnService.kt`)**: Demonstrates protocol-level monitoring using `VpnService`. Captures metadata (destination IPs, packet sizes) without TLS breaking.
- **Detection Logic (`logic/MediaDetectionLogic.kt`)**: Classifies streams into Direct Media, Adaptive Streams (HLS/DASH), or DRM-protected content.
- **HLS Downloader (`logic/HlsDownloader.kt`)**: Demonstrates the reconstruction of HLS streams by merging segments.

## Research Objectives

1. **Protocol vs UI Security**: Shows that while a PWA might restrict the UI (e.g., hiding download buttons), the underlying network protocol still exposes media segments.
2. **VPN Boundaries**: Demonstrates that without MitM (Man-in-the-Middle) and certificate pinning bypass, detailed request paths in HTTPS traffic remain opaque to a standard VPN, requiring app-level interception (WebView) for full path visibility.
3. **DRM Identification**: Identifies the presence of cryptographic protection that prevents unauthorized decryption and downloading.

## How to Build

1. Open the project in **Android Studio**.
2. Sync Gradle dependencies.
3. Build and Run on a device with **Android 10+ (API 29+)**.

## Mandatory User Action

- Analysis only starts after the user enters a URL and taps **Analyze**.
- Downloads ONLY proceed after **user confirmation** via a dialog.
- No background automation or silent scraping is implemented.
