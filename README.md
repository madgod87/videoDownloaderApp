# FikFap Downloader v4.4

A professional, high-performance Android media acquisition utility designed for modern research and data preservation. FikFap Downloader combines a secure VPN-based traffic analyzer with a robust background download engine to simplify the retrieval of HLS and direct media streams.

## 🚀 Key Features

- **Professional Light Theme**: A clean, high-contrast Material 3 interface designed for maximum readability and ease of use.
- **Background Stability**: Downloads are handled by a dedicated Foreground Service, ensuring that operations are never interrupted by system events like incoming phone calls or app switching.
- **Secured Media Library**: A centralized hub to manage your artifacts. Features high-quality video thumbnails and direct actions (Play, Share, Delete).
- **Multi-Select Bulk Sharing**: Long-press any artifact in the library to enter targeting mode, allowing you to share multiple files simultaneously.
- **Smart VPN Tunneling**: Automatically establishes a secure tunnel for traffic analysis and collapses it upon download completion to preserve battery and data.
- **System Diagnostics**: Dedicated real-time console for monitoring network detections and engine status.

## 🛠️ Technology Stack

- **Language**: Kotlin
- **Networking**: Android VpnService & Traffic Interception
- **Media Engine**: RxFFmpeg (for robust HLS/M3U8 processing)
- **UI Components**: Material Design 3, CoordinatorLayout
- **Image Loading**: Glide (for instant video thumbnails)

## 📦 How to Build

1. Clone the repository.
2. Open in **Android Studio Bumblebee** or newer.
3. Sync Gradle and build the `app-debug.apk`.
4. Ensure the device has **Android 11+** for optimal Scoped Storage performance.

## ⚖️ Usage Note

This tool is designed for educational and data preservation purposes. Always ensure you have the rights to the content you are analyzing or securing.
