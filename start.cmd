@echo off
setlocal EnableDelayedExpansion
REM ============================================================================
REM Start HR Agent dev mode: mcp-server (20811) + backend (20810) + frontend (5173)
REM Pure-cmd launcher: NO PowerShell dependency.
REM Use this when powershell.exe is slow to spawn (AV scanning etc.) - start.ps1
REM cannot help you if PowerShell itself cannot start.
REM Keep this file ASCII-only.
REM ============================================================================
cd /d "%~dp0"

REM --- refuse to start twice (netstat is instant; Get-NetTCPConnection took 8s+) ---
netstat -ano | findstr "LISTENING" | findstr ":20811 " >nul && (echo [X] Port 20811 already in use. Run stop.cmd first.& exit /b 1)
netstat -ano | findstr "LISTENING" | findstr ":20810 " >nul && (echo [X] Port 20810 already in use. Run stop.cmd first.& exit /b 1)
netstat -ano | findstr "LISTENING" | findstr ":5173 " >nul && (echo [X] Port 5173 already in use. Run stop.cmd first.& exit /b 1)

if not exist "mcp-server\target\adk-mcp-server-1.0.0.jar" (
    echo [X] Missing mcp-server\target\adk-mcp-server-1.0.0.jar - build it once:
    echo     cd mcp-server ^&^& mvn -B -DskipTests package
    exit /b 1
)

REM drop stale logs so failure-pattern checks below cannot match yesterday's run
del /q mcp-server-run.log mcp-server-run.err.log app-run.log app-run.err.log frontend-run.log frontend-run.err.log 2>nul

echo [*] Starting mcp-server + backend + frontend via start-services.vbs (detached) ...
wscript.exe "%~dp0start-services.vbs"

REM --- wait for MCP server FIRST (backend caches agents; MCP must be up before it) ---
set /a tries=0
:wait_mcp
curl.exe -sf -o nul --max-time 3 http://127.0.0.1:20811/actuator/health >nul 2>&1 && goto mcp_ok
findstr /C:"APPLICATION FAILED TO START" mcp-server-run.log >nul 2>&1 && (echo [X] MCP server startup failed. See mcp-server-run.log& exit /b 1)
set /a tries+=1
if !tries! geq 30 (echo [X] Timed out waiting for MCP server. See mcp-server-run.log& exit /b 1)
ping -n 3 127.0.0.1 >nul
goto wait_mcp
:mcp_ok
echo [OK] MCP server ready: http://localhost:20811/mcp

REM --- wait for backend (mvn spring-boot:run compiles first, allow ~3 min) ---
set /a tries=0
:wait_backend
curl.exe -sf -o nul --max-time 3 http://127.0.0.1:20810/api/health >nul 2>&1 && goto backend_ok
findstr /C:"APPLICATION FAILED TO START" app-run.log >nul 2>&1 && (echo [X] Backend startup failed. See app-run.log& exit /b 1)
findstr /C:"BUILD FAILURE" app-run.log >nul 2>&1 && (echo [X] Backend build failed. See app-run.log& exit /b 1)
set /a tries+=1
if !tries! geq 60 (echo [X] Timed out waiting for backend. See app-run.log& exit /b 1)
ping -n 3 127.0.0.1 >nul
goto wait_backend
:backend_ok
echo [OK] Backend ready: http://localhost:20810/

REM --- wait for frontend dev server ---
set /a tries=0
:wait_frontend
curl.exe -sf -o nul --max-time 3 http://localhost:5173/ >nul 2>&1 && goto frontend_ok
set /a tries+=1
if !tries! geq 30 (echo [X] Timed out waiting for frontend. See frontend-run.log& exit /b 1)
ping -n 2 127.0.0.1 >nul
goto wait_frontend
:frontend_ok
echo [OK] Frontend ready: http://localhost:5173/

echo.
echo [OK] Dev mode started.
echo   MCP server:  http://localhost:20811/mcp (external tools: weather/Amap)
echo   Backend API: http://localhost:20810/api
echo   Frontend UI: http://localhost:5173/
echo   Logs: mcp-server-run.log, app-run.log, frontend-run.log
exit /b 0
