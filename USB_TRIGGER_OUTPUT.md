# USB LT/RT output in Android 0.23.0

## Added Cronus Zen mode

**USB adjustable (Cronus Zen)** uses the matching XInput-mode Wi-Fi Pico firmware.
The phone Pico connects to Zen, while the chair Pico can stay on the Adaptive
Controller. The proposed combined setup uses Xbox Controller Assist; use the
phone Pico's external-right-stick configuration to leave camera movement with
the chair. This path needs actual Zen/Xbox acceptance before gameplay.

This choice preserves fractional trigger requests all the way to the Pico.
Profiles and existing connection settings are unchanged. Switching the output
choice affects only connection/output; all existing XAC connection options stay.
The full-press XAC setup below remains available with its corresponding firmware.

Format 5 is reserved for adjustable USB triggers. HELLO/usable READY carry only
capability byte 4. STATE remains 80 bytes with independent 0–65535 trigger words.
Unavailable READY, neutral-before-input, ACK/STOP/STOPPED, expiry, page/profile
cleanup and five-minute reconnection rules match the earlier formats. XAC
firmware cannot arm format 5, and Zen firmware cannot arm an XAC trigger mode.

## Setup

1. Install the updated Wi-Fi phone Pico firmware. Leave the chair Pico firmware alone.
2. Use the phone Pico in the Adaptive Controller's left USB port and the chair Pico in its right port.
3. In Xbox Accessories, edit the active Adaptive Controller profile: assign X1 to LT and X2 to RT. Select that profile on the controller.
4. In the app, open Bridge menu → Connection settings. Choose the Pico SCX2 destination and LT and RT output → USB buttons (full press). Keep the existing Pico address and port, then Connect.
5. Check LT by itself, RT by itself, both together, release, and the chair right stick in Xbox Accessories before gameplay. Console mapping labels and hardware acceptance need to be confirmed on the actual XAC.

Any positive requested pressure becomes a full press; zero releases. For example
`LT:0.5,RT:0.7` becomes full LT and full RT. Saved payloads and profiles retain
0.5 and 0.7. Hold Toggle, timed release, repeat, Macro Mode (default 200 ms),
TouchAim, overlapping ownership, page/profile cleanup, Release all, reconnect
intent and the five-minute background policy continue to use the existing paths.
USB buttons (full press) do not supply proportional accelerator/brake pressure.
The separate Adjustable (circuit) mode requires the planned analog trigger
circuit and its firmware. USB adjustable (Cronus Zen) uses the new route above.

Practice shows the requested percentages. The connected Pico status shows the
selected trigger mode and its resulting output (0%/100% in USB mode).

## Wire contract

- Legacy format 2 and pressure format 3 remain byte-for-byte compatible.
- USB buttons (full press) explicitly requests format 4. HELLO and usable READY require capability byte 7 = 2. An unavailable READY has capability 0, no HID-ready flag and a zero challenge.
- STATE is exactly 80 bytes. The final unsigned 16-bit LT and RT words are independently 0 or 65535. Intermediate values, unsupported button bits and malformed lengths are rejected.
- Existing ten gamepad button bits, D-pad and axes are unchanged. Guide, Share, mouse and keyboard are not sent on this route.
- ACK, STOP and STOPPED have no capability bits. A fresh neutral STATE must be acknowledged before new touches are accepted. Old sessions, endpoints and callbacks cannot activate the new session.
- There is no silent fallback between formats. Old firmware cannot arm a USB-trigger session. Adjustable mode cannot silently use USB triggers.

The app converts only at the output boundary, after resolving overlapping
owners. A second owner can keep LT active when Aim + fire releases. Fractional
requested values and the layout file remain untouched.

## Verification scope

Automated unit checks cover cross-language golden packet bytes, strict mode and
capability validation, binary conversion, saved-setting migration, independent
ownership, queue retirement and connection recovery. Android emulator checks
exercise actual MotionEvents, macros, lifetime cleanup, saved intent and UDP.
They do not establish physical Android touch behavior or XAC USB acceptance.
