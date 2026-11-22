# PC receiver verification

This package supports Windows virtual-controller output and Mac keyboard/mouse mapping. The automated work below ran on macOS with fake native input. It does **not** claim that the ViGEmBus installer, Windows virtual controller or a physical Windows game has been tested on this machine.

- 334 receiver tests passed after the Mac addition (291 existing + 43 Mac-specific), including real localhost face/voice sockets. Two Windows-only native ABI tests are excluded on macOS and remain in the source for Windows.
- Coverage includes the original Android protocol, phone/face/voice ownership, all controller cleanup paths, SCPC1 schema/handshake, stale/reordered/duplicate datagrams, pointer/scroll deduplication, partial triggers, both stick axis conventions, keyboard/mouse releases, receiver restart and phone lease expiry.
- Android lease tests cover optional negotiation, fresh renewals, expiry, blocking late PHONE and legacy OUTPUT/STICK frames, neutral same-session reactivation with a newer sequence, unchanged older-client behavior, and an old socket’s reordered release arriving after fresh activation without clearing new inputs.
- Five setup/launcher tests verify platform rejection, using the environment's Python, pinned requirements, the skip-driver-installer environment variable, visible failure handling and no launch if dependencies are missing. These simulate Windows; they do not execute `.bat` files or install a driver.
- The full iOS Core suite passed 1,090 tests, including 10 new PC protocol tests. Twenty actual app-model checks cover PC output, pressure, Camera Follow, neutral reconnect, saved-layout preservation, preserving Pico trigger preference, and unrestricted Practice Guide/Share controls.

- An actual iOS simulator connected over UDP to the production SCPC1 parser with a recording backend. Seventeen changed snapshots across two sessions verified W, simultaneous mouse-left + A, RT 50% then release, right-stick movement/centering, touchpad movement, unsupported Guide/Share filtering, Stop and background release. Stop returned to a fresh neutral session without manual reconnect. This exercises actual iOS networking and parsing, not Windows input injection.

Run the full receiver tests from this folder using `python run_tests.py`. Tests replace native outputs with recording fakes; local socket access is required. `test_pc_udp_peer.py --record output.jsonl` starts a recording-only localhost SCPC1 peer for an iOS simulator, not a Windows gaming receiver.

## Mac verification boundary

- 43 focused Mac tests passed. They cover default and custom mapping validation, all letter/digit key codes, modifier-first chord presses and modifier-last releases, shared key/mouse ownership, stick diagonals and neutral, retained right-stick pointer motion, bounded motion after a scheduler pause, trigger thresholds, mapping save/backup, Accessibility refusal/revocation, native event allocation failure, launcher platform checks, SCPC1 snapshots, duplicate movement suppression, phone stop, Android negotiation and lease expiry, and stale release barriers.
- The full 334-test suite passed on macOS with local loopback socket access on the verified official CPython 3.13.17 runtime. Two Windows-only ABI cases remain excluded here. The previously published Windows run passed the prior 293-test source; it does not establish a Windows run of this new revision.
- Actual CoreGraphics keyboard, mouse and wheel event creation/release succeeded on this Apple Silicon Mac, without calling CGEventPost. Mocked ABI checks verify key codes, modifier flags, drag event types, relative delta fields and signed scroll data. **No native keyboard/mouse input was posted during these automated checks.**
- The optional mapping editor opened under official Python 3.13.17: all 58 widgets fit in its 620×650 window, including Save/Cancel; no input widget was undersized. A programmatic Save against an isolated fixture changed A from Space to F, preserved the previous mapping and left the source configuration unchanged. No native keyboard/mouse events were posted.
- Mac-specific regressions also verify Android scroll uses native lines rather than Windows’ ×120 scale, fractional pointer/scroll deltas accumulate and clear on release, invalid deltas fail before native output, and SCPC1 takes the receiver lock before the Mac output lock to match Camera Follow.
- Native permission approval and real game behavior still need the Mac acceptance steps below. A green phone connection confirms protocol communication, not that the foreground game accepted input. Some games reject generated events or require different key mappings.

For a recording-only real-UDP integration test: `python3 mac_recording_peer.py --bind 127.0.0.1 --port 42734 --duration 300 --log capture.jsonl`. This imports the production receiver and Mac mapper, but replaces every native output with JSONL recording. It stops after the bounded duration or Ctrl+C. Do not use it for actual play.

## Remaining Mac acceptance

1. Follow README's Mac setup and grant Accessibility to the launcher you use. Run `python3 start_mac_receiver.py --check` if you want a permission/configuration check that sends no input.
2. In a blank unsaved document/empty desktop, verify ordinary keys, Command/Control/Shift combinations, each mouse click, touchpad drag and scrolling. Only the intended foreground application should receive input.
3. In a keyboard/mouse-compatible game, match the mapping editor's bindings to the game, then verify both sticks, triggers, Stick+, repeat/macros, aim/fire and Camera Follow. Analog trigger pressure and native Xbox controller detection are not provided on Mac.
4. Verify lift, Stop, page/profile changes, disconnect, phone backgrounding and temporary Wi-Fi loss. All phone-owned holds should clear on their documented lease. Reconnect must start neutral. Close normally with Ctrl+C before changing mappings or moving to another app.

## Remaining Windows verification

1. On an Intel/AMD64 Windows PC, review/install the official final ViGEmBus driver and Python, then run Setup and Start as described in README.md.
2. Verify the virtual controller appears in `joy.cpl`. Confirm both sticks' four directions and return-to-center; buttons, D-pad, LT and RT; then release everything.
3. Test keyboard and mouse in an empty document/desktop, including lift, Stop, page/profile changes, and briefly turning phone Wi-Fi off. Nothing should remain held or replay when reconnecting.
4. Test one game accepting Xbox controller input. Check simultaneous controls, Stick+, repeat, macro, retained sticks, aim/fire and drive/steer. Confirm the game's bindings match the chosen profile.
5. Verify no old bundled ViGEm driver installation launches during Setup. If Windows or the driver rejects the installation, capture the exact message; do not bypass operating-system security settings.
