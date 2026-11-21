@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if errorlevel 1 (
  python setup_receiver.py
) else (
  py -3 setup_receiver.py
)
if errorlevel 1 echo Setup did not finish. Read the message above and README.md.
pause
