@echo off
setlocal
REM ============================================================================
REM Stop HR Agent dev mode: kill processes listening on 20810, 5173, 20811.
REM Pure-cmd version: NO PowerShell dependency. Keep ASCII-only.
REM ============================================================================
set stopped=0
for %%p in (20810 5173 20811) do (
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%%p "') do (
        echo [*] Stopping PID %%a on port %%p ...
        taskkill /PID %%a /F >nul 2>&1 && set stopped=1
    )
)
if %stopped%==1 (echo [OK] Stopped.) else (echo [i] Nothing was running.)
