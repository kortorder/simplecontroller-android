"""Friendly Windows entry point. Does not install drivers or elevate itself."""
import importlib.util
from pathlib import Path
import runpy
from setup_receiver import DRIVER_URL, platform_problem

def main():
    problem = platform_problem()
    if problem:
        print(problem); return 1
    missing = [name for name in ('keyboard','mouse','pyautogui','vgamepad')
               if importlib.util.find_spec(name) is None]
    if missing:
        print('Missing packages: '+', '.join(missing))
        print('Double-click Setup-Windows-Receiver.bat first.'); return 1
    print('Keep this window open while playing. Use a trusted private Wi-Fi network.')
    print('Allow Windows Firewall access for Private networks only if prompted.')
    print('Do not forward the receiver port to the internet.')
    try:
        runpy.run_path(str(Path(__file__).with_name('simple_controller_receiver.py')), run_name='__main__')
    except KeyboardInterrupt:
        return 0
    except Exception as error:
        print('\nReceiver could not start:', error)
        print('If this mentions ViGEmBus or a virtual controller, install/restart the free driver:')
        print(DRIVER_URL)
        print('If the port is in use, close another receiver window and try again.')
        return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
