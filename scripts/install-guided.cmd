@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-guided.ps1" %*
exit /b %errorlevel%
