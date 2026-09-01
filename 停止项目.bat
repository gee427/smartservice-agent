@echo off
title SmartService-Agent Stop
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-dev.ps1"
pause
