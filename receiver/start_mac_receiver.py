"""Free Mac receiver launcher; no package installer, driver, elevation or hooks."""
import argparse
import os
from pathlib import Path
import platform
import runpy
import struct
import sys
from mac_output import QuartzOutput, configure, load_mapping


def platform_problem():
    if sys.platform != 'darwin': return 'Use this launcher on a Mac. For Windows use Start-Windows-Receiver.bat.'
    if sys.version_info < (3,10): return 'Install Python 3.10 or newer from https://www.python.org/downloads/macos/ then open this launcher again.'
    if platform.machine().lower() not in ('arm64','aarch64','x86_64') or struct.calcsize('P') != 8:
        return 'This receiver supports 64-bit Intel and Apple Silicon Macs.'
    try: major=int(platform.mac_ver()[0].split('.')[0])
    except (ValueError,IndexError): return 'Could not determine the macOS version.'
    if major < 13: return 'This receiver requires macOS 13 Ventura or newer.'
    return None


def main(argv=None):
    parser=argparse.ArgumentParser(description='Simple Controller Mac keyboard/mouse receiver')
    parser.add_argument('--mapping',type=Path,default=Path(__file__).with_name('Mac-Mapping.json'))
    parser.add_argument('--check',action='store_true',help='Check setup without starting a receiver or sending inputs')
    args=parser.parse_args(argv)
    problem=platform_problem()
    if problem: print(problem);return 1
    try:
        mapping=load_mapping(args.mapping)
        output=QuartzOutput()
    except Exception as error:
        print('Mac receiver setup could not be loaded:',error);return 1
    print('Simple Controller for Mac — keyboard and mouse mapping')
    print('Existing controller layouts are translated to game keyboard/mouse controls.')
    print('This does not create a native Xbox controller or analog triggers.')
    if not output.trusted():
        print('\nOne-time permission is needed before this receiver can send input:')
        print('1. Open Apple menu → System Settings → Privacy & Security → Accessibility.')
        print('2. Use + to add Terminal (Applications → Utilities → Terminal), then enable it.')
        print('   If macOS lists Python or your terminal app instead, enable that launcher.')
        print('3. Quit this receiver window, then double-click Start-Mac-Receiver.command again.')
        print('No Input Monitoring, Full Disk Access, administrator mode, or security bypass is needed.')
        return 2
    if args.check:
        print('Mac, Python, mapping and Accessibility checks passed. No inputs sent.');return 0
    print('\nKeep this window open. Use Player 1 in the Android app.')
    print('In the phone app choose Mac • keyboard and mouse / Windows & Mac receiver, enter the Mac IPv4 below, port 42734.')
    print('Use the same trusted private Wi-Fi; allow Local Network / incoming connections if macOS asks.')
    print('Do not forward this port on your router. Ctrl+C stops and releases receiver inputs.')
    print('Default: left stick WASD; right stick mouse; LT right-click; RT left-click.')
    print('Use Configure-Mac-Receiver.command to match your game\'s key bindings.')
    runtime=configure(output,mapping)
    old_backend=os.environ.get('SIMPLE_CONTROLLER_BACKEND')
    old_log=os.environ.get('LOCALAPPDATA')
    os.environ['SIMPLE_CONTROLLER_BACKEND']='mac'
    os.environ['LOCALAPPDATA']=str(Path.home()/'Library'/'Application Support')
    runtime.start()
    try:
        runpy.run_path(str(Path(__file__).with_name('simple_controller_receiver.py')),run_name='__main__')
    except KeyboardInterrupt: pass
    except Exception as error:
        print('Receiver stopped:',error)
        print('If port 42734 is in use, close another receiver window and try again.')
        return 1
    finally:
        runtime.close()
        if old_backend is None: os.environ.pop('SIMPLE_CONTROLLER_BACKEND',None)
        else: os.environ['SIMPLE_CONTROLLER_BACKEND']=old_backend
        if old_log is None: os.environ.pop('LOCALAPPDATA',None)
        else: os.environ['LOCALAPPDATA']=old_log
    if runtime.failure:
        print('Mac input output stopped:',runtime.failure);return 1
    return 0

if __name__=='__main__':raise SystemExit(main())
