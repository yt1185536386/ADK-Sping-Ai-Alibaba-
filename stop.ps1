# Stop HR Agent dev mode (kill processes listening on ports 20810, 5173, 20811)
# Usage: powershell -ExecutionPolicy Bypass -File .\stop.ps1
# If powershell.exe itself is slow/broken on this machine, use stop.cmd instead.
# NOTE: keep this file ASCII-only. PowerShell 5.1 reads BOM-less files as ANSI.
$ports = @(20810, 5173, 20811)
$stopped = $false

# netstat instead of Get-NetTCPConnection: the cmdlet took 8+ seconds per call
# on this machine (observed 2026-10-26); netstat -ano returns instantly.
$listenTable = netstat -ano | Select-String 'LISTENING'

foreach ($port in $ports) {
    $procIds = $listenTable | Select-String ":$port\s" |
        ForEach-Object { [int](($_.Line -split '\s+')[-1]) } |
        Sort-Object -Unique
    if (-not $procIds) {
        Write-Host "Port $port is not listening." -ForegroundColor Yellow
        continue
    }
    foreach ($procId in $procIds) {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($p) {
            Write-Host "Stopping PID $procId $($p.ProcessName) (port $port) ..." -ForegroundColor Cyan
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            $stopped = $true
        }
    }
}
Start-Sleep -Seconds 2

# verify
$listenTable2 = netstat -ano | Select-String 'LISTENING'
$stillRunning = @()
foreach ($port in $ports) {
    if ($listenTable2 | Select-String ":$port\s") { $stillRunning += $port }
}
if ($stillRunning.Count -gt 0) {
    Write-Host "Failed to stop ports: $($stillRunning -join ', '). Please kill manually." -ForegroundColor Red
    exit 1
} else {
    if ($stopped) { Write-Host "Stopped." -ForegroundColor Green } else { Write-Host "Nothing was running." -ForegroundColor Yellow }
}
