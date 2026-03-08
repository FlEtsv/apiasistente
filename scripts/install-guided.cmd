@echo off
rem Wrapper Windows para lanzar el instalador guiado de PowerShell.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-guided.ps1" %*
exit /b %errorlevel%
