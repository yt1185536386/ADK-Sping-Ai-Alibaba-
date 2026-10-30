# Start HR Agent in dev mode: mcp-server (port 20811) + backend (spring-boot:run) + frontend (vite dev)
# Usage: powershell -ExecutionPolicy Bypass -File .\start.ps1
# If powershell.exe itself is slow/broken on this machine, use start.cmd instead (zero PowerShell dependency).
# NOTE: keep this file ASCII-only. PowerShell 5.1 reads BOM-less files as ANSI,
# so any non-ASCII text here (even inside comments) breaks the parser.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# --- Refuse to start twice ----------------------------------------------------
# Use netstat instead of Get-NetTCPConnection: the cmdlet took 8+ seconds per
# call on this machine (observed 2026-10-26); netstat -ano returns instantly.
$listenTable = netstat -ano | Select-String 'LISTENING'
function Test-PortBusy([int]$port) {
    return [bool]($listenTable | Select-String ":$port\s")
}
foreach ($port in @(20811, 20810, 5173)) {
    if (Test-PortBusy $port) {
        Write-Host "Port $port already in use. Run .\stop.ps1 (or stop.cmd) first." -ForegroundColor Yellow
        exit 1
    }
}

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

# Fix duplicated env keys (Path/PATH, https_proxy/HTTPS_PROXY, ...) which make
# Start-Process fail with "An item with the same key has already been added":
# delete then re-set each variable so duplicate keys collapse into one.
foreach ($e in ([System.Environment]::GetEnvironmentVariables('Process')).GetEnumerator()) {
    $k = [string]$e.Key
    $v = [string]$e.Value
    [System.Environment]::SetEnvironmentVariable($k, $null, 'Process')
    [System.Environment]::SetEnvironmentVariable($k, $v, 'Process')
}

# DB creds live in User-level env vars (set once, not committed). Start-Process
# inherits the *process* env, which on a fresh shell may not include User vars
# merged in - pull them forward so the child always sees them.
# These are shared by BOTH the backend and mcp-server.
foreach ($k in @('DB_USERNAME', 'DB_PASSWORD', 'DB_HOST', 'DB_PORT', 'DB_NAME', 'API_KEY_ENCRYPTION_KEY')) {
    $proc = [System.Environment]::GetEnvironmentVariable($k, 'Process')
    if ([string]::IsNullOrEmpty($proc)) {
        $user = [System.Environment]::GetEnvironmentVariable($k, 'User')
        if (-not [string]::IsNullOrEmpty($user)) {
            [System.Environment]::SetEnvironmentVariable($k, $user, 'Process')
        }
    }
}

$mcpJar = Join-Path $root 'mcp-server\target\adk-mcp-server-1.0.0.jar'
if (-not (Test-Path $mcpJar)) {
    Write-Host "Missing $mcpJar - build it once: cd mcp-server; mvn -B -DskipTests package" -ForegroundColor Red
    exit 1
}

# Drop stale logs so the failure-pattern checks below cannot match an old run.
foreach ($f in @('mcp-server-run.log', 'mcp-server-run.err.log', 'app-run.log', 'app-run.err.log', 'frontend-run.log', 'frontend-run.err.log')) {
    Remove-Item (Join-Path $root $f) -Force -ErrorAction SilentlyContinue
}

# --- Launch all services via VBS wrapper under wscript.exe -------------------
# wscript spawns cmd processes detached from this console, so java/mvn/node keep
# running after this script (and the AI terminal session) exits. Start-Process
# or Start-Job children get killed when the automation terminal tears down.
Write-Host "Starting mcp-server + backend + frontend via start-services.vbs (detached) ..." -ForegroundColor Cyan
$vbs = Join-Path $root 'start-services.vbs'
if (-not (Test-Path $vbs)) {
    Write-Host "Missing $vbs" -ForegroundColor Red
    exit 1
}
Start-Process -FilePath "wscript.exe" -ArgumentList "`"$vbs`"" -WindowStyle Hidden

# Fast health probes: HttpClient with 3s timeout instead of Invoke-WebRequest
# (IWR in PS 5.1 renders a progress bar per call and is much slower).
Add-Type -AssemblyName System.Net.Http
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(3)
function Test-Health([string]$url) {
    try { return $http.GetAsync($url).Result.IsSuccessStatusCode } catch { return $false }
}
function Wait-Service([string]$name, [string]$url, [string]$logFile, [int]$maxTries) {
    for ($i = 0; $i -lt $maxTries; $i++) {
        Start-Sleep -Seconds 2
        if (Test-Health $url) { return $true }
        $log = Get-Content (Join-Path $root $logFile) -Raw -Encoding utf8 -ErrorAction SilentlyContinue
        if ($log -match 'APPLICATION FAILED TO START|BUILD FAILURE') {
            Write-Host "$name startup failed. See $root\$logFile" -ForegroundColor Red
            exit 1
        }
    }
    Write-Host "Timed out waiting for $name. See $root\$logFile" -ForegroundColor Red
    exit 1
}

# MCP server must be up BEFORE the backend: the backend caches module agents,
# and a common-module agent built while MCP is down keeps 0 tools until restart.
if (Wait-Service 'MCP server' 'http://127.0.0.1:20811/actuator/health' 'mcp-server-run.log' 30) {
    Write-Host "MCP server ready: http://localhost:20811/mcp" -ForegroundColor Green
}
if (Wait-Service 'Backend' 'http://127.0.0.1:20810/api/health' 'app-run.log' 60) {
    Write-Host "Backend ready: http://localhost:20810/" -ForegroundColor Green
}
if (Wait-Service 'Frontend' 'http://localhost:5173/' 'frontend-run.log' 30) {
    Write-Host "Frontend ready: http://localhost:5173/" -ForegroundColor Green
}

Write-Host ""
Write-Host "Dev mode started." -ForegroundColor Green
Write-Host "  MCP server:   http://localhost:20811/mcp (external tools: weather/Amap)" -ForegroundColor Cyan
Write-Host "  Backend API:  http://localhost:20810/api" -ForegroundColor Cyan
Write-Host "  Frontend UI:  http://localhost:5173/" -ForegroundColor Cyan
Write-Host "  Logs: mcp-server -> mcp-server-run.log, backend -> app-run.log, frontend -> frontend-run.log" -ForegroundColor Cyan
