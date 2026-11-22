# Simple Controller: free Windows receiver

Use your iPhone or Android phone as a controller over your home Wi-Fi. **No Pico, adaptive controller or other extra hardware is required for PC play.** This receiver software, Python and a virtual controller driver must run on the gaming PC. The phone does not send game controls to an unprepared PC automatically.

The package extends the original Android receiver with iPhone support. Xbox Adaptive Controller and Pico console connections remain separate and unchanged.

## First setup on the Windows gaming PC

Target: an Intel/AMD **64-bit Windows PC**, with a supported Windows installation (Windows 11 recommended) and 64-bit Python 3.10 or newer. Windows ARM, Mac and Linux native game output are not supported by this package. The new iPhone route has been checked with automated and simulated receiver tests; actual Windows driver installation and game recognition still need a Windows test.

1. Download **PC-Receiver.zip** from the app repository's Releases page. Right-click it → **Extract All**. Open the extracted folder. Do not run files from inside the ZIP.
2. Install 64-bit Python from [python.org](https://www.python.org/downloads/windows/). Keep its Python launcher/install manager enabled. If Python is already installed, you can try the next step first.
3. Install the free **ViGEmBus 1.22.0** virtual Xbox-controller driver from [its official final release](https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0). Open **Assets** and choose its Windows setup program. Review its license and Windows permission prompt yourself. Restart if requested. This is an archived, end-of-life third-party driver; [read the maintainer's notice](https://docs.nefarius.at/projects/ViGEm/End-of-Life/). It is not a Microsoft-supported controller driver and future Windows compatibility is not guaranteed.
4. Double-click **Setup-Windows-Receiver.bat**. It installs the free Python dependencies in `.venv` inside this folder. Internet is needed for this one-time step. The script deliberately skips the older ViGEm installer bundled with `vgamepad`; it does not accept driver agreements or silently install a driver.
5. Double-click **Start-Windows-Receiver.bat**. Keep its window open. If Windows Firewall asks, allow **Private networks only** on your trusted home network. Do not disable the firewall or forward a router port.
6. The receiver window shows an **IP Address** and **Port 42734**. Keep these visible for your phone setup.

## Connect your phone

Put the phone and PC on the same trusted home network. The PC may use Ethernet to the same router.

**iPhone:** Bridge menu → Bridge settings → **Windows PC • free receiver**. Type the receiver's IPv4 address and port **42734**, then Save and connect. Allow Local Network access if iOS asks. A neutral handshake enables controls. Once saved, reopening the app can reconnect automatically; old held actions are never restored.

**Android:** choose **Windows receiver**, enter the same IP and **42734**, then Connect. Enter the port printed by this receiver even if an older app initially shows a different number. Use the latest app and receiver together for network-loss release protection. Android's original Windows protocol and profiles remain compatible.

**Test before a game:** press Windows+R, type `joy.cpl` and press Enter. Select the virtual Xbox controller → Properties. Try A, both sticks and LT/RT from the phone, then lift or use Stop/Release all. Check that everything returns to neutral. A trigger may appear as a combined axis in this Windows panel; a game's own controller settings can show each trigger separately. Try a keyboard button in a blank text document and the touchpad on an empty desktop. Then start your game and choose controller input if required.

## What works through the receiver

- A/B/X/Y, LB/RB, Menu/View, D-pad, both sticks and stick clicks.
- Adjustable LT/RT, including combined aim/fire and drive/steer controls.
- Hold Toggle, repeat/turbo, timed macros, Stick+, retained sticks and recentering: these run in the app and are sent as controller input.
- Keyboard keys, mouse buttons, touchpad movement and scrolling, plus Camera Follow.
- Xbox Guide/logo and Share are not supported by the iPhone PC route. A game's binding or input method may differ from the profile example; remap the profile or game as needed.

Use one phone for player 1 at a time. The original Android receiver's optional face/voice integrations are preserved; their separately owned held inputs are not canceled by an iPhone lease expiring. Phone Stop is not an emergency stop for other independent assistive programs.

## Stop and troubleshoot

- Tap **Stop / Release all** in the app before switching away. Use Disconnect to end the connection. The receiver clears phone input on a timed lease if the network disappears (iPhone: 0.5 seconds; new Android lease: 3 seconds). Reconnect starts neutral. Older Android clients/receivers without lease negotiation do not have this guarantee; update both.
- To stop the receiver normally, select its window and press **Ctrl+C**, then Enter if prompted. This releases receiver output. Avoid force-killing the process while keys are held.
- No connection: check the printed IP/port, same network, iOS Local Network permission and Private-network firewall access. Guest/hotel Wi-Fi can isolate devices. Do not put a public IP in the app.
- Connection but no gamepad: finish ViGEmBus installation, restart if asked, and check `joy.cpl`. The receiver creates virtual Xbox 360 pads; choose the active pad/player in the game. Not all games accept virtual input or mixed keyboard/controller input.
- “Python not found”: install 64-bit Python from the official link, then reopen Setup. “Missing packages”: run Setup again. “Port in use”: close the other receiver window.
- These local UDP protocols do not authenticate users or encrypt input. Use only on a trusted private network. Anyone who can reach the receiver port may be able to send input. No internet/router port forwarding is needed.
- Diagnostic logs stay on the PC under `%LOCALAPPDATA%\SimpleControllerReceiver\touchpad_logs`. They may include control commands and local network addresses; review before sharing. No cloud account is required.

## Developers and verification

`simple_controller_receiver.py` is the original compatible Windows receiver. `ios_pc_protocol.py` implements SCPC1 bounded full-state snapshots, neutral handshake, per-session sequence checks, cumulative pointer/scroll deduplication and expiry. `ios_pc_adapter.py` feeds its existing source-owned output layer. `phone_output_protocol.py` adds optional Android `PHONE_LEASE_V1` without changing unnegotiated older clients.

Tests use recording backends, never native keyboard or game input on a Mac. `test_pc_udp_peer.py` is an explicitly recording-only localhost peer for simulator tests; it is not the gaming receiver. See [VERIFICATION.md](VERIFICATION.md) for the tested boundary and Windows checklist.

Free dependencies and their upstream licenses are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Driver and Python installers are obtained from their official publishers; they are not bundled in this ZIP.
