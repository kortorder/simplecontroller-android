# PC receiver verification

This package is prepared for Windows; the automated work below ran on macOS with fake native input. It does **not** claim that the ViGEmBus installer, Windows virtual controller or a physical Windows game has been tested on this machine.

- 291 receiver tests passed, including real localhost face/voice sockets. Two Windows-only native ABI tests are excluded on macOS and remain in the source for Windows.
- Coverage includes the original Android protocol, phone/face/voice ownership, all controller cleanup paths, SCPC1 schema/handshake, stale/reordered/duplicate datagrams, pointer/scroll deduplication, partial triggers, both stick axis conventions, keyboard/mouse releases, receiver restart and phone lease expiry.
- Android lease tests cover optional negotiation, fresh renewals, expiry, blocking late PHONE and legacy OUTPUT/STICK frames, neutral same-session reactivation with a newer sequence, unchanged older-client behavior, and an old socket’s reordered release arriving after fresh activation without clearing new inputs.
- Five setup/launcher tests verify platform rejection, using the environment's Python, pinned requirements, the skip-driver-installer environment variable, visible failure handling and no launch if dependencies are missing. These simulate Windows; they do not execute `.bat` files or install a driver.
- The full iOS Core suite passed 1,090 tests, including 10 new PC protocol tests. Twenty actual app-model checks cover PC output, pressure, Camera Follow, neutral reconnect, saved-layout preservation, preserving Pico trigger preference, and unrestricted Practice Guide/Share controls.

- An actual iOS simulator connected over UDP to the production SCPC1 parser with a recording backend. Seventeen changed snapshots across two sessions verified W, simultaneous mouse-left + A, RT 50% then release, right-stick movement/centering, touchpad movement, unsupported Guide/Share filtering, Stop and background release. Stop returned to a fresh neutral session without manual reconnect. This exercises actual iOS networking and parsing, not Windows input injection.

Run the full receiver tests from this folder using `python run_tests.py`. Tests replace native outputs with recording fakes; local socket access is required. `test_pc_udp_peer.py --record output.jsonl` starts a recording-only localhost SCPC1 peer for an iOS simulator, not a Windows gaming receiver.

## Remaining Windows verification

1. On an Intel/AMD64 Windows PC, review/install the official final ViGEmBus driver and Python, then run Setup and Start as described in README.md.
2. Verify the virtual controller appears in `joy.cpl`. Confirm both sticks' four directions and return-to-center; buttons, D-pad, LT and RT; then release everything.
3. Test keyboard and mouse in an empty document/desktop, including lift, Stop, page/profile changes, and briefly turning phone Wi-Fi off. Nothing should remain held or replay when reconnecting.
4. Test one game accepting Xbox controller input. Check simultaneous controls, Stick+, repeat, macro, retained sticks, aim/fire and drive/steer. Confirm the game's bindings match the chosen profile.
5. Verify no old bundled ViGEm driver installation launches during Setup. If Windows or the driver rejects the installation, capture the exact message; do not bypass operating-system security settings.
