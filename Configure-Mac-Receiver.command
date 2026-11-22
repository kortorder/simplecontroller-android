#!/bin/bash
cd -- "$(dirname -- "$0")" || exit 1
if ! command -v python3 >/dev/null 2>&1; then
  echo 'Install Python 3.10 or newer from https://www.python.org/downloads/macos/ first.'
else
  python3 configure_mac_receiver.py
fi
read -r -p 'Press Return to close this window. '
