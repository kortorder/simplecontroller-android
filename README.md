# Simple Controller for Android

Simple Controller lets you play with one finger. Pick a starter for the kind of game you play, then move or reassign its buttons, sticks and touchpads to fit your reach. You can try every layout in Practice on your phone before connecting a receiver.

## Install the Android community preview

**[Download the signed Android APK, 0.19.0-preview4](https://github.com/kortorder/simplecontroller-android/releases/download/v0.19.0-preview4/SimpleController-Android-0.19.0-preview4.apk)** · [Release notes](https://github.com/kortorder/simplecontroller-android/releases/tag/v0.19.0-preview4) · [SHA-256 checksums](https://github.com/kortorder/simplecontroller-android/releases/download/v0.19.0-preview4/SHA256SUMS.txt)

Open the APK on a phone running **Android 7 or newer** and follow the installer prompts. If Android asks, allow the browser or file manager you used to open the download to install it. To keep your saved profiles, install build 26 over your existing community preview. It can also be installed alongside the original Android app.

## Choose a game starter

On first launch, tap **Shooter**, **Racing**, **Sports** or **Open World** to open your own editable copy. **Use standard controller** opens the original setup. To add another starter later, open **All controls → Profiles → Game starters**. Choosing Racing again creates *Racing 2* and preserves the first copy.

Every starter has a **Menus** page with a D-pad and menu buttons. In Open World, switch between **On foot** and **Drive**. Tap **Return to play** from Menus to return to the page you came from. The action names follow common Xbox mappings. Use Edit to match your game's control scheme.

This release also includes per-action delays and repeats, TouchAim stage sensitivity and entry confirmation, and floating-ring boost and fine-aim options. In the source project, the guides are `PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md` and `FLOATING_RING_OPTIONS_PLAIN_ENGLISH.txt`. The optional GTA 6 Prep sample and its import guide are in `profiles/`.

## Connect a receiver

**Bridge menu** opens connection options. The [Wi-Fi Pico bridge](https://github.com/kortorder/simplecontroller-pico-wifi-bridge) connects the phone to an Xbox Adaptive Controller. The [Omni2 chair joystick bridge](https://github.com/kortorder/omni2-pico-xbox-joystick) is a separate project for using a chair joystick as a right stick. The Windows receiver's Python sources are included with the Android project.

The community build passed 775 unit tests and 44 emulator tests. The emulator checks covered starter selection, saving separate copies, and navigating between game and menu pages. Physical Android and Xbox gameplay tests are pending.

## Build from source

[Download the complete Android Studio project](https://github.com/kortorder/simplecontroller-android/releases/download/v0.19.0-preview4/SimpleController-Android-source-0.19.0-preview4.zip) and open the extracted `simplecontroller-android` folder. To work with the editable Git history, [download the Git bundle](https://github.com/kortorder/simplecontroller-android/releases/download/v0.19.0-preview4/SimpleController-Android-0.19.0-preview4.bundle) and clone it locally.

```sh
git clone -b source-import SimpleController-Android-0.19.0-preview4.bundle simplecontroller-android
```

From the project folder, use JDK 21, Android SDK Platform 36 and Build Tools 35.0.0 to run the tests and build the community APK.

```sh
bash gradlew testCommunityDebugUnitTest assembleCommunityDebug lintCommunityDebug
```

The debug APK is written to `app/build/outputs/apk/community/debug/app-community-debug.apk`. Local builds use your own debug signing key. Use the signed release APK to update an installed community preview. Dependency notices are in `docs/THIRD_PARTY_NOTICES.txt`.
