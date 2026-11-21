@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo Please run Setup-Windows-Receiver.bat first. See README.md.
  pause
  exit /b 1
)
".venv\Scripts\python.exe" start_receiver.py
pause
