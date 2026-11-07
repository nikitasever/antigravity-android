# Antigravity PC Bridge Watchdog
# Automatically restarts pc-bridge/server.js whenever node is killed or port 8787 is unreachable.

$BridgeDir = $PSScriptRoot
$NodeExe = "C:\Users\st811210\AppData\Local\Programs\nodejs\node.exe"
if (-not (Test-Path $NodeExe)) {
    $NodeExe = "node"
}
$env:PATH = "C:\Users\st811210\AppData\Local\Programs\nodejs;C:\Users\st811210\AppData\Local\agy\bin;$env:PATH"

$ServerJs = Join-Path $BridgeDir "server.js"
$WatchdogLog = Join-Path $BridgeDir "watchdog.log"
$ServerOutLog = Join-Path $BridgeDir "bridge_stdout.log"
$ServerErrLog = Join-Path $BridgeDir "bridge_stderr.log"
$PidFile = Join-Path $BridgeDir "watchdog.pid"

Set-Content -Path $PidFile -Value $PID -Force

function Write-WatchdogLog([string]$msg) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $msg"
    Add-Content -Path $WatchdogLog -Value $line -ErrorAction SilentlyContinue
}

Write-WatchdogLog "Watchdog started (PID=$PID) watching $ServerJs using $NodeExe"

while ($true) {
    $healthy = $false
    try {
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:8787/api/health" -Method Get -TimeoutSec 2 -ErrorAction Stop
        if ($resp -and $resp.ok -eq $true) {
            $healthy = $true
        }
    } catch {
        $healthy = $false
    }

    if (-not $healthy) {
        Write-WatchdogLog "Bridge on 127.0.0.1:8787 is down. Starting $NodeExe server.js..."
        try {
            $proc = Start-Process -FilePath $NodeExe -ArgumentList @("`"$ServerJs`"") -WorkingDirectory $BridgeDir -WindowStyle Hidden -RedirectStandardOutput $ServerOutLog -RedirectStandardError $ServerErrLog -PassThru
            Write-WatchdogLog "Started node server.js (PID=$($proc.Id)). Waiting for startup..."
            Start-Sleep -Seconds 4
        } catch {
            Write-WatchdogLog "Failed to start node server.js: $($_.Exception.Message)"
        }
    }

    Start-Sleep -Seconds 3
}
