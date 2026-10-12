# BeeBeep Droid

Qt-free, pure Kotlin Android client for the [BeeBEEP](https://www.beebeep.net) LAN messenger
(package `com.galandras12.droidbeep`, Material 3 / Jetpack Compose UI). It speaks the BeeBEEP 5.9.2
network protocol (proto 96) and talks to the Windows/Linux/macOS desktop clients.

## Features
- Discovery by UDP broadcast + multicast (224.0.64.75, port 36475), manual/fixed addresses, Hive
- HELLO handshake with ECDH K-163 key agreement + AES-256 (bit-compatible with the C++ code), network password
- Public chat, private chats, group chats (create / edit / leave / member sync)
- Delivery + read receipts, typing indicator, buzz, important and source-code messages, offline message queue
- Status (online/busy/away + message), vCard with photo, colour
- File, folder and voice-message transfer in both directions (resume-capable wire format), file sharing lists
- Foreground service, notifications, Android share-sheet integration, English + Hungarian UI

Not implemented: desktop sharing, BeeBOX (answers "unavailable"), live voice/video calls, plugins.

## Verification
- `CryptoVectorsTest`: ECDH/AES/SHA vectors produced by the original C++ sources.
- `DesktopInteropTest` (`BEEBEEP_INTEROP=1`): ran against a real BeeBEEP 5.9.2 desktop build — messages,
  group chat, buzz, status and file transfer in both directions (byte-identical) worked.
- `ScreenshotTest` renders the screens with Roborazzi (`app/build/shots`).

Build: `./gradlew assembleDebug` (JDK 17, Android SDK 35). Licence: GPL v3 (see LICENSE).
