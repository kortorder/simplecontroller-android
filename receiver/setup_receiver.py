"""One-time dependency setup; driver license/install remains a user action."""
import os
from pathlib import Path
import platform
import struct
import subprocess
import sys
import venv

DRIVER_URL = 'https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0'

def platform_problem():
    if sys.platform != 'win32':
        return 'This receiver runs on Windows, not on a Mac or Linux. Open it on your gaming PC.'
    if platform.machine().upper() not in ('AMD64', 'X86_64') or struct.calcsize('P') != 8:
        return 'Use 64-bit Python on an Intel/AMD Windows PC. Windows ARM and 32-bit Python are not supported by this package.'
    if sys.version_info < (3, 10):
        return 'Install Python 3.10 or newer (64-bit) from https://www.python.org/downloads/windows/ and try again.'
    return None

def main():
    problem = platform_problem()
    if problem:
        print(problem); return 1
    root = Path(__file__).resolve().parent
    environment = root / '.venv'
    print('Preparing Simple Controller receiver software in this folder.')
    print('No extra controller hardware is needed for PC play.')
    print('A separate, free ViGEmBus virtual controller driver is required.')
    print('Review/install its official final release yourself: ' + DRIVER_URL)
    print('ViGEmBus is an archived legacy driver. See README.md before installing.')
    try:
        venv.EnvBuilder(with_pip=True).create(environment)
        interpreter = environment / 'Scripts' / 'python.exe'
        env = os.environ.copy()
        # Never launch the older driver installer bundled by vgamepad implicitly.
        env['VGAMEPAD_SKIP_VIGEMBUS_INSTALL'] = 'true'
        subprocess.run([str(interpreter), '-m', 'pip', 'install', '--disable-pip-version-check',
                        '-r', str(root / 'requirements-windows.txt')], check=True, env=env)
    except (OSError, subprocess.CalledProcessError) as error:
        print('Setup failed:', error)
        print('Check internet access and write access to this extracted folder, then run Setup again.')
        return 1
    print('\nPython packages are installed. Finish the ViGEmBus driver installation if needed,')
    print('restart Windows if its installer requests it, then double-click Start-Windows-Receiver.bat.')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
