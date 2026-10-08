@echo off
rem Gera dist\NetSpeedBar-Setup-<versao>.exe (pode abrir com dois cliques)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gerar-instalador.ps1"
pause
