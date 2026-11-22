# Simple Controller: free Windows and Mac receiver

Use your iPhone or Android phone as a controller over your home Wi-Fi. **No Pico, adaptive controller or other extra hardware is required for PC play.** This receiver software and Python must run on the gaming computer. Windows additionally needs a virtual controller driver; Mac uses built-in keyboard/mouse output. The phone does not send game controls to an unprepared PC automatically.

The package extends the original Android receiver with iPhone support. Xbox Adaptive Controller and Pico console connections remain separate and unchanged.

## First setup on the Windows gaming PC

Target: an Intel/AMD **64-bit Windows PC**, with a supported Windows installation (Windows 11 recommended) and 64-bit Python 3.10 or newer. Windows ARM and Linux are not supported by this package. Mac uses the separate keyboard/mouse route below; it does not create a native gamepad. The new iPhone route has been checked with automated and simulated receiver tests; actual Windows driver installation and game recognition still need a Windows test.

1. Download **PC-Receiver.zip** from the app repository's Releases page. Right-click it → **Extract All**. Open the extracted folder. Do not run files from inside the ZIP.
2. Install 64-bit Python from [python.org](https://www.python.org/downloads/windows/). Keep its Python launcher/install manager enabled. If Python is already installed, you can try the next step first.
3. Install the free **ViGEmBus 1.22.0** virtual Xbox-controller driver from [its official final release](https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0). Open **Assets** and choose its Windows setup program. Review its license and Windows permission prompt yourself. Restart if requested. This is an archived, end-of-life third-party driver; [read the maintainer's notice](https://docs.nefarius.at/projects/ViGEm/End-of-Life/). It is not a Microsoft-supported controller driver and future Windows compatibility is not guaranteed.
4. Double-click **Setup-Windows-Receiver.bat**. It installs the free Python dependencies in `.venv` inside this folder. Internet is needed for this one-time step. The script deliberately skips the older ViGEm installer bundled with `vgamepad`; it does not accept driver agreements or silently install a driver.
5. Double-click **Start-Windows-Receiver.bat**. Keep its window open. If Windows Firewall asks, allow **Private networks only** on your trusted home network. Do not disable the firewall or forward a router port.
6. The receiver window shows an **IP Address** and **Port 42734**. Keep these visible for your phone setup.

## First setup on a Mac

Target: **macOS 13 Ventura or newer**, Apple Silicon or Intel, and **Python 3.10 or newer**. This route is free and needs no Pico, adaptive controller, paid driver, or Windows computer. It sends keyboard and mouse input. **It does not create a virtual Xbox controller or preserve analog trigger pressure.** Games must accept keyboard/mouse controls and generated events; compatibility is game-dependent.

1. Download **PC-Receiver.zip** from the app repository's Releases page and double-click the ZIP to extract it. Keep all extracted files together. The release ZIP preserves the launchers' executable permissions; prefer it over downloading individual scripts from the GitHub webpage.
2. Install Python from [the official Mac downloads](https://www.python.org/downloads/macos/) if needed. Choose the universal2 installer for Intel and Apple Silicon. The python.org installer includes Tk for the optional visual mapping editor. There is no `pip` setup and no separate Mac driver.
3. Double-click **Start-Mac-Receiver.command**. The window first checks Python, macOS, the mapping file and Accessibility permission. If it says Python is too old, install the current Python and open a new launcher window.
4. If Accessibility is missing, open **Apple menu → System Settings → Privacy & Security → Accessibility**. Use **+** to add **Terminal** from **Applications → Utilities**, then enable it. If macOS lists Python or the terminal app you use instead, enable that launcher. Reopen the receiver after granting access. This permission lets your phone send keys and mouse actions to your Mac. Do not enable Full Disk Access, Input Monitoring or administrator mode. [Apple's permission guide](https://support.apple.com/guide/mac-help/allow-accessibility-apps-to-access-your-mac-mh43185/mac) explains the setting.
5. Keep the receiver window open. It prints the Mac's **IPv4 address** and **Port 42734**. Allow Local Network or incoming connections for this receiver if macOS asks, on your trusted private network. Connect the phone as described below.
6. Before a game, use a blank unsaved text document and empty desktop to check a letter button, touchpad and click. Then verify the game's key bindings and try a short movement. Stop/Release all and disconnect must release the phone's actions.

If a source download lost the launchers' executable bits, open Terminal, type `bash ` (including the space), drag **Start-Mac-Receiver.command** from Finder into the Terminal window, then press Return. For the editor, do the same with **Configure-Mac-Receiver.command**. This runs the local reviewed script without changing macOS security settings. If macOS blocks a downloaded item, review its source and use the normal System Settings **Open Anyway** flow if offered; do not disable Gatekeeper or remove quarantine protection globally.

### Use your existing profiles on Mac

The receiver translates your existing controller controls, so saved layouts and phone payloads stay intact. Defaults are a starting point, not promised bindings for every game:

| Phone control | Default Mac action |
| --- | --- |
| Left stick | W / A / S / D |
| Right stick, including right-stick aim pads | Move the mouse/camera |
| LT / RT | Right / left mouse button |
| A / B / X / Y | Space / left Control / R / E |
| LB / RB | Q / G |
| Menu / View | Escape / Tab |
| D-pad | Arrow keys |
| Left-stick click / right-stick click | Left Shift / V |

Any LT/RT value above zero becomes a held mouse click. For example RT 30% and RT 100% both mean left-click held; this is **not analog acceleration**. Left-stick movement becomes digital keys. Right-stick distance changes pointer speed beyond a 20% deadzone. Touchpad, scroll, native keyboard/mouse payloads, Hold Toggle, macros, repeat, Stick+ and Camera Follow continue through the same receiver command engine. The game's mouse settings also affect aiming. Two players share one Mac desktop; use **Player 1**, and do not expect two-player native controller support.

To change bindings without writing code, stop the receiver and open **Configure-Mac-Receiver.command**. Choose a key or mouse button beside each controller action, adjust camera speed/deadzone if desired, and click **Save mappings**. Reopen the receiver to apply them. **OFF** disables a mapped action. The previous configuration is saved as `Mac-Mapping.previous.json`; the current one is `Mac-Mapping.json`. The editor changes no phone profiles. LWIN/RWIN mean Command; LALT/RALT mean Option. Key codes identify physical keys, so resulting text depends on your Mac's selected keyboard layout.

## Connect your phone

Put the phone and PC on the same trusted home network. The PC may use Ethernet to the same router.

**iPhone:** Bridge menu → Bridge settings → **Mac • keyboard and mouse** for a Mac, or **Windows PC • free receiver** for Windows. Type the receiver's IPv4 address and port **42734**, then Save and connect. Allow Local Network access if iOS asks. A neutral handshake enables controls. Once saved, reopening the app can reconnect automatically; old held actions are never restored.

**Android:** choose **Windows / Mac receiver**, enter the same IP and **42734**, then Connect. Enter the port printed by this receiver even if an older app initially shows a different number. Use the latest app and receiver together for network-loss release protection. Android's original Windows protocol and profiles remain compatible.

**Windows gamepad test before a game:** press Windows+R, type `joy.cpl` and press Enter. Select the virtual Xbox controller → Properties. Try A, both sticks and LT/RT from the phone, then lift or use Stop/Release all. Check that everything returns to neutral. A trigger may appear as a combined axis in this Windows panel; a game's own controller settings can show each trigger separately. Try a keyboard button in a blank text document and the touchpad on an empty desktop. Then start your game and choose controller input if required.

## Windows controller output

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
- Mac connects but no output: check Accessibility for Terminal/Python, reopen the receiver, and verify your game accepts keyboard/mouse events. The Mac route will not appear as an Xbox controller in gamepad settings.
- Windows connection but no gamepad: finish ViGEmBus installation, restart if asked, and check `joy.cpl`. The receiver creates virtual Xbox 360 pads; choose the active pad/player in the game. Not all games accept virtual input or mixed keyboard/controller input.
- “Python not found”: install 64-bit Python from the official link, then reopen Setup. “Missing packages”: run Setup again. “Port in use”: close the other receiver window.
- These local UDP protocols do not authenticate users or encrypt input. Use only on a trusted private network. Anyone who can reach the receiver port may be able to send input. No internet/router port forwarding is needed.
- Diagnostic logs stay on Windows under `%LOCALAPPDATA%\SimpleControllerReceiver\touchpad_logs`, or on Mac under `~/Library/Application Support/SimpleControllerReceiver/touchpad_logs`. They may include control commands and local network addresses; review before sharing. No cloud account is required.

## Developers and verification

`simple_controller_receiver.py` preserves the original compatible Windows receiver and selects the Mac backend only through the separate Mac launcher. `mac_output.py` uses Python ctypes with built-in CoreGraphics, plus source-owned controller-to-key/mouse mappings. It does not install a system extension, hook global input, or request a virtual-HID entitlement. `ios_pc_protocol.py` implements SCPC1 bounded full-state snapshots, neutral handshake, per-session sequence checks, cumulative pointer/scroll deduplication and expiry. `ios_pc_adapter.py` feeds its existing source-owned output layer. `phone_output_protocol.py` adds optional Android `PHONE_LEASE_V1` without changing unnegotiated older clients.

Tests use recording backends, never native keyboard or game input on a Mac. `test_pc_udp_peer.py` is an explicitly recording-only localhost peer for simulator tests; it is not the gaming receiver. `mac_recording_peer.py --port 42734 --duration 300 --log capture.jsonl` uses the production receiver and Mac mapper with recording outputs only; it never posts native events. See [VERIFICATION.md](VERIFICATION.md) for the tested boundary and Windows checklist.

Free dependencies and their upstream licenses are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Driver and Python installers are obtained from their official publishers; they are not bundled in this ZIP.
