# Simple Controller for Android

**Install on Android:** [Download the community APK](SimpleController-Android-0.24.0-preview9.apk). Install it over an earlier community preview to retain its saved profiles.

**Start here:** [Connection FAQ](FAQ.txt) · [Beginner videos](videos/README.md) · [Windows PC receiver download](PC-Receiver.zip).

**Developer downloads:** [Version 0.24.0-preview9 source ZIP](SimpleController-Android-0.24.0-preview9-source.zip) · [Git bundle](SimpleController-Android-0.24.0-preview9-source.bundle) · [SHA-256 checksums](SOURCE-SHA256SUMS.txt).

Simple Controller is a customizable controller built for playing with one finger. Make your own layouts with buttons, sticks, touchpads and pages, or start with the included GTA 6 Prep layout. Try controls in Practice before connecting a receiver.

## Install the Android community preview

The community APK is **SimpleController-Android-0.24.0-preview9.apk**. Downloads and beginner videos are on [community downloads](https://github.com/kortorder/simplecontroller-android/releases).

Download the APK on **Android 7 or newer**, open it and follow the phone’s installer prompts. If prompted, allow the app you used to open this APK to install it. Version **0.24.0-preview9 (31)** uses the same community application ID as earlier previews. Install it over that preview to keep its saved data. The community preview also installs alongside the original app, which has its own application ID and profiles.

## Beginner help and PC safety in 0.24

New installations open an eight-page illustrated tour with Next, Back and Skip. Existing profiles open normally. Use **Support** in Bridge menu or All controls to replay the tour, read Windows PC and console setup instructions, or open the FAQ, receiver download and videos.

Windows PC play needs the free [receiver software](receiver/) on the same network; enter its printed IPv4 address and port (42734 by default). No Pico or adaptive controller is needed for this route. With the updated receiver, Android negotiates a three-second input lease and renews it while active. Lost acknowledgements release local actions and reconnect from neutral. Old receiver compatibility remains available with a visible warning that Wi-Fi-loss cleanup is unavailable.

This preview preserves the 0.23 Android feature set and adds help and PC protection. It does not claim complete parity with later iPhone releases. The instructional videos demonstrate the iPhone app; consult [FAQ.txt](FAQ.txt) for platform differences and supported hardware.

## Cronus Zen adjustable USB candidate in 0.23

The added **USB adjustable (Cronus Zen)** trigger choice keeps the percentages in existing payloads, such as `LT:0.5,RT:0.7`. It requires the matching XInput-mode phone Pico firmware and a Cronus Zen. It uses a separate connection format and never silently falls back to full presses or the analog circuit. Existing profiles, layouts and connection settings are not rewritten.

The original Adaptive Controller USB mode, trigger circuit mode, Windows receiver and legacy ConsoleBridge remain available. For the chair setup, the planned route keeps the chair Pico connected to the Adaptive Controller and combines that controller with the Zen using Xbox Controller Assist. The phone Pico's external-right-stick build keeps its own right-stick output neutral so the chair remains in charge. **Zen and combined-chair hardware acceptance is still pending**; this is a software candidate, not a verified plug-and-play hardware claim. See [trigger modes and compatibility](USB_TRIGGER_OUTPUT.md).

## USB LT and RT in 0.22.0

Open **Bridge menu → Connection settings**, choose **Pico W / Xbox bridge (SCX2)**, then choose **LT and RT output → USB buttons (full press)**. Update the phone Pico firmware first. In Xbox Accessories, map its **X1 to LT** and **X2 to RT** and activate that controller profile. Choosing an app output mode does not change the chair Pico's firmware or nudge mappings.

USB mode sends any positive trigger request as a full press. Your existing Aim + fire payload, such as `LT:0.5,RT:0.7`, presses both fully; release and timed-release actions still work. The saved 50% and 70% values are kept for later adjustable hardware. The connected readout shows the resulting 0% or 100% USB output; Practice continues to show requested pressure.

**Off** preserves the previous USB-only setup. **Adjustable (circuit)** keeps the proportional route for the extra circuit connected to the XAC's LT/RT jacks. Selecting USB does not fall back to older firmware, and selecting Adjustable does not silently become a digital press. Existing settings migrate to Off or Adjustable; new installations start Off. See [USB trigger setup and compatibility](USB_TRIGGER_OUTPUT.md).

## Keeping the Pico connected

After you connect the Pico bridge, you can change pages and profiles, open the
editor or connection settings, and use Release all without tapping Connect again.
Each transition clears held buttons, sticks, trigger pressure and macros. Controls
resume after the bridge confirms a fresh neutral state; lift and touch again to
start a new action. A temporary Wi-Fi or receiver interruption pauses controls and
retries automatically.

Leaving the app stops its output immediately. Returning in **less than five
minutes** reconnects with neutral controls. After five minutes, use Connect again.
This also applies when Android closes the app process. **Disconnect** and
**Practice** cancel automatic reconnection. Saved layouts and positions stay as
configured. See [connection behavior](BRIDGE_CONNECTION_BEHAVIOR.md).

## Game starters in 0.19.0

Choose Shooter, Racing, Sports or Open World when you first open the app. Tap a category to start using your own copy. Choose **Use standard controller** for the original setup. You can also open **All controls → Profiles → Game starters** at any time. Each selection creates a fresh copy; for example the second Racing layout is saved as Racing 2.

Each starter includes a Menus page for the D-pad and menu buttons. Its named game actions use common Xbox assignments. Controls can be moved and reassigned in Edit. Practice runs on the phone; playing on a console or PC uses the appropriate configured receiver.

## New in 0.18.0

You can give individual actions independent delays, repeats, and release timing. For example, `LT:0.5,RT:0.7(D100)(R200)` holds LT immediately while RT begins after 100 ms and repeats every 200 ms. In TouchAim, a shrinking contact can leave the Medium stage before you finish lifting. HRD now starts at that stage exit and the remaining delay continues through finger lift.

Each manual TouchAim stage can have its own sensitivity and entry confirmation. Floating rings offer closer or separate boost zones for aiming and movement, an option to ignore swipes across Finish, and direct-touch fine aiming. See the [timing guide](PAYLOAD_MODIFIERS_AND_STAGE_TUNING.md) and [floating-ring options](FLOATING_RING_OPTIONS_PLAIN_ENGLISH.txt).

## Find your controls

The bridge, camera, and controller tools are now visible on the play screen. **Bridge menu** opens connection options, **Camera Follow** shows its current state, and **All controls** opens the full-height list, including the camera strength, profiles and pages. Practice keeps inputs on the phone. The Pico bridge does not generate Camera Follow movement; the existing Windows connection supports its own camera behavior.

To try the [GTA 6 Prep sample](profiles/GTA-6-Prep.simplecontroller-profile.json), download the file on your phone, use **Import controller profile**, and keep your existing profiles when importing. It includes lower-left sticks, a repeat at full forward and a direct Aim + fire pad. The sample game bindings are provisional. [Profile notes](profiles/GTA-6-Prep-Android.txt)

## Build from source

Open the extracted project in Android Studio or use JDK 21, Android SDK Platform 36 and Build Tools 35.0.0. From the repository root, run:

```sh
bash gradlew testCommunityDebugUnitTest assembleCommunityDebug lintCommunityDebug
```

The build produces `app/build/outputs/apk/community/debug/app-community-debug.apk`. Local builds use your own debug signing key. Use the signed APK from the release to update an installation of the community preview.

The Gradle wrapper, original Windows receiver Python sources, tests, and profile tools are included. [Dependency notices](docs/THIRD_PARTY_NOTICES.txt) · [Previous community preview](https://github.com/kortorder/simplecontroller-android/releases/tag/v0.17.2-preview2)

## Pico projects

The [Wi-Fi Pico bridge](https://github.com/kortorder/simplecontroller-pico-wifi-bridge) connects the app to an Xbox Adaptive Controller, with a new Cronus Zen candidate mode. A separate [Omni2 chair joystick bridge](https://github.com/kortorder/omni2-pico-xbox-joystick) turns a chair joystick into the right stick. The existing phone and chair USB stick/button routes, including full-press X1/X2 trigger mappings, have been checked on the owner’s Xbox using iPhone. Cronus Zen and the adjustable trigger circuit still need hardware checks. Android behavioral checks use an emulator; physical Android testing is pending.

## Macro Mode and automatic release

Open Edit, hold a Button to open its settings, and turn on **Macro Mode** under
Behavior. One tap completes its actions after you lift. Every command defaults to
a 200 millisecond press. Add `(U100)` to choose a 100 millisecond press for one
action, or `(D500)` to start it half a second after the initial touch. For example,
`X360A(U100),X360A(D500)(U100),X360B(D800)(U100)` performs three separate presses.

Macro Mode is an individual button option. Extra taps while it runs are ignored.
Release all, leaving the app, editing, and changing page/profile cancel its work.
See `MACRO_MODE_AND_PAYLOAD_PORTING.md` for command timing and compatibility.
