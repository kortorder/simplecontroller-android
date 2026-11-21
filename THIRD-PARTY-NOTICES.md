# Receiver provenance and dependencies

The controller server and its camera/face/voice/phone ownership modules derive from the user's original Simple Controller Android project, shared with the owner's permission. New iPhone transport and Android lease changes are maintained alongside both app repositories. Preserve the containing repository's license and attribution; this document does not impose a new license on the original author's work.

These dependencies are installed separately, not copied into this source package:

| Component | Purpose | Upstream license and source |
| --- | --- | --- |
| Python | Runtime | [PSF license](https://docs.python.org/3/license.html) |
| vgamepad 0.2.0 | Virtual Xbox 360 controller client | [MIT](https://github.com/yannbouteiller/vgamepad/blob/main/LICENSE) |
| keyboard 0.13.5 | Keyboard input | [MIT](https://github.com/boppreh/keyboard/blob/master/LICENSE.txt) |
| mouse 0.7.1 | Mouse/wheel input | [MIT](https://github.com/boppreh/mouse/blob/master/LICENSE.txt) |
| PyAutoGUI 0.9.54 | Original mouse handling | [BSD 3-Clause](https://github.com/asweigart/pyautogui/blob/master/LICENSE.txt) |
| ViGEmBus 1.22.0 | Required virtual gamepad driver | [BSD 3-Clause](https://github.com/nefarius/ViGEmBus/blob/master/LICENSE) |

Python packages can install their own dependencies, each with its own license. `pip` preserves installed package metadata/licenses. The setup script does not redistribute the dependency implementations or driver installer.

ViGEmBus is archived and end-of-life. Its official final driver release and notice are linked in README.md. “Free” describes the receiver and dependency cost; it does not promise compatibility with every Windows update or game.
