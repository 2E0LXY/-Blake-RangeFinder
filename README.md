# Blake UK RangeFinder Pro

Independent Android application for **FNIRSI IR40**, with Blake UK branding.

This repository is published publicly at the owner's request. The application is independently compiled from source using the Android SDK, rather than modifying or resigning the FNIRSI manufacturer's APK.

## Features
Bluetooth LE discovery and connection diagnostics; readings and measurement history; local project management; measured rectangular floor plans; area and volume calculators; PDF/CSV export; photo annotation; JSON backups. The exact IR40 characteristic and commands require device testing.

## Android build
JDK 17, Android Gradle Plugin 8.13.2, Gradle 8.13, Android SDK 35.

The GitHub Actions workflow runs unit tests, builds a debug-signed Android APK, verifies its signature and alignment and attaches the APK to a **prerelease**. This is a test APK only. Its debug signing key may change between CI runs, so production updates require a stable release signing key.

## Important limitations
- Device protocol and live Bluetooth measurements have **not** been verified with a physical IR40.
- Software cannot exceed the IR40's hardware measurement accuracy.
- Reference and unit control commands are deliberately not issued without protocol confirmation.
- A rectangular floor-plan calculation assumes the room is rectangular.
- Retain the original manufacturer's app while testing the beta.

## Copyright
© 2026 Blake UK Ltd. All rights reserved. FNIRSI and IR40 are trademarks of their respective owners.
