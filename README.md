# Simple Controller — Android community preview

A customizable touch controller for people who want to adapt controls to their reach and access needs. Built on [Colonelwheel’s original SimpleController](https://github.com/Colonelwheel/Simplecontroller), with editable layouts, pages, retained sticks, Hold Toggle, repeated actions, touchpads, aim/fire controls and an optional GTA preparation layout.

## Download

**[Download the Android app (APK)](https://github.com/kortorder/simplecontroller-android/releases/download/v0.17.0-preview1/SimpleController-Android-0.17.0-preview1.apk)**

**[Release page, complete package and instructions](https://github.com/kortorder/simplecontroller-android/releases/tag/v0.17.0-preview1)**

- Install `SimpleController-Android-0.17.0-preview1.apk`. It installs as **Simple Controller Preview**, alongside the original app.
- Download `SimpleController-Android-0.17.0-preview1.zip` for the app, GTA layout, instructions, source, checksums and test record. Open **START-HERE.txt** inside.
- Developers: download **SimpleController-Android-source-0.17.0-preview1.zip** from the release assets. This named archive contains the exact app/build source used for the APK, tests, Gradle wrapper and build instructions. This repository hosts the distribution page; GitHub’s automatically generated “Source code” archives contain this page, not the app project. The complete package also includes a verified patch from upstream commit `71a647c04dbae863be86244b291b09baf9f2c2d9`.

## Getting started

The app declares Android 7.0 or later support. Start in **Practice**, which shows local input without sending anything to another device. Import the optional GTA profile using **Edit → Load → Import profile file**. After import, the top tools scroll sideways to reach Load, Save and orientation settings; **Release all** sits beside that strip. Adjust button size and placement to suit the person using it.

The example includes tiny circular buttons, retained movement, repeated A at full-forward travel, direct Aim + fire, a floating aiming ring and rectangular driving/steering pads. GTA 6 mappings are provisional and editable, not verified controls for the game.

## Preview status

Version **0.17.0-preview1** passed **508 JVM tests and 40 Android emulator tests in each debug flavor**, plus nine profile-conversion checks. Selected emulator import, layout, orientation, retained-stick, release and ring checks also passed.

Physical Android touch, reach and contact calibration have not been tested. The manual review is not a complete screen-reader or large-font audit. Real Wi-Fi, receiver and console hardware acceptance is pending. See the release’s **Verification.json** and **Feature-parity.md** for the exact scope. Please share this as a **community preview for feedback**.

Windows receiver support and the optional Pico W/Xbox Adaptive Controller bridge are separate output routes. Adjustable LT/RT through the XAC trigger jacks needs matching firmware and the external analog circuit; a Pico alone does not provide trigger-jack pressure. The Pico route currently requires Connect again after a safety stop.

## Source and attribution

Original project authorship, notices and the confirmed upstream recovery baseline are preserved. Dependency notices are included. This distribution does not add or replace an application license or imply endorsement by the original project, Microsoft or Rockstar Games.

## Feedback

[Report a problem or suggest an improvement](https://github.com/kortorder/simplecontroller-android/issues). Include the device model, Android version, preview version and the smallest steps that reproduce a problem. Please omit passwords and private profile backups from public reports.
