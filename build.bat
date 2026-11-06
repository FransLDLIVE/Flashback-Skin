@echo off
title Building Flashback Skin Timeline
cd /d "%~dp0"
echo Building Flashback Skin Timeline. This takes a few minutes (it downloads Java 25 and Minecraft 26.3 files).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
echo.
pause
