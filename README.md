# Simple Controller for Android

Simple Controller is a customizable controller built for playing with one finger. Make your own layouts with buttons, sticks, touchpads and pages, or start with the included GTA 6 Prep layout. Try controls in Practice before connecting a receiver.

## Install the Android community preview

**[Download the signed Android APK](https://github.com/kortorder/simplecontroller-android/releases/download/v0.17.2-preview2/SimpleController-Android-0.17.2-preview2.apk)** · [Release notes and checksums](https://github.com/kortorder/simplecontroller-android/releases/tag/v0.17.2-preview2)

Download the APK on **Android 7 or newer**, open it and follow the phone’s installer prompts. If prompted, allow the app you used to open this APK to install it. Version **0.17.2-preview2 (24)** uses the same community application ID as preview 1. Install it over that preview to keep its saved data. The community preview also installs alongside the original app, which has its own application ID and profiles.

## Find your controls

The bridge, camera, and controller tools are now visible on the play screen. **Bridge menu** opens connection options, **Camera Follow** shows its current state, and **All controls** opens the full-height list, including the camera strength, profiles and pages. Practice keeps inputs on the phone. The Pico bridge does not generate Camera Follow movement; the existing Windows connection supports its own camera behavior.

To try the [GTA 6 Prep sample](https://github.com/kortorder/simplecontroller-android/releases/download/v0.17.2-preview2/GTA-6-Prep.simplecontroller-profile.json), download the file on your phone, use **Import controller profile**, and keep your existing profiles when importing. It includes lower-left sticks, a repeat at full forward and a direct Aim + fire pad. The sample game bindings are provisional. The source ZIP contains the profile guide in `profiles/GTA-6-Prep-Android.txt`.

## Build from source

**[Download the complete source ZIP](https://github.com/kortorder/simplecontroller-android/releases/download/v0.17.2-preview2/SimpleController-Android-source-0.17.2-preview2.zip)** · [Source checksum](https://github.com/kortorder/simplecontroller-android/releases/download/v0.17.2-preview2/SHA256SUMS.txt)

Download and extract **SimpleController-Android-source-0.17.2-preview2.zip**. If you cloned this repository, you can extract the archive of that name from the checkout. Open the extracted `simplecontroller-android` folder in Android Studio, or build with JDK 21, Android SDK Platform 36 and Build Tools 35.0.0. From that folder, run the following command.

```sh
bash gradlew testCommunityDebugUnitTest assembleCommunityDebug lintCommunityDebug
```

The build produces `app/build/outputs/apk/community/debug/app-community-debug.apk`. Local builds use your own debug signing key. Use the signed APK from the release to update an installation of the community preview.

The Gradle wrapper, original Windows receiver Python sources, tests, and profile tools are included. Read `docs/THIRD_PARTY_NOTICES.txt` in the extracted project for dependency notices. [Previous community preview](https://github.com/kortorder/simplecontroller-android/releases/tag/v0.17.0-preview1)

For a complete Git checkout, [download the portable Git bundle](https://github.com/kortorder/simplecontroller-android/raw/refs/heads/main/SimpleController-Android-0.17.2-preview2.bundle). In the folder where you downloaded it, run `git clone -b source-import SimpleController-Android-0.17.2-preview2.bundle simplecontroller-android-source`. That checkout already contains the full project.

## Pico projects

The [Wi-Fi Pico bridge](https://github.com/kortorder/simplecontroller-pico-wifi-bridge) connects the app to an Xbox Adaptive Controller. A separate [Omni2 chair joystick bridge](https://github.com/kortorder/omni2-pico-xbox-joystick) turns a chair joystick into the right stick. Console acceptance and adjustable trigger hardware testing are pending. Android behavioral checks use an emulator; physical Android testing is pending.
