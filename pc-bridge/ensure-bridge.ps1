# Ensures the Antigravity PC Bridge and its Watchdog are running and registered for auto-start.

$BridgeDir = $PSScriptRoot
$WatchdogScript = Join-Path $BridgeDir "start-bridge-watchdog.ps1"
$PidFile = Join-Path $BridgeDir "watchdog.pid"

# 1. Register Windows Scheduled Task (runs outside ephemeral terminal job objects)
$TaskName = "AntigravityPcBridgeWatchdog"
try {
    $Action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$WatchdogScript`"" -WorkingDirectory $BridgeDir
    $TriggerLogon = New-ScheduledTaskTrigger -AtLogOn
    $Settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $TriggerLogon -Settings $Settings -Force -ErrorAction SilentlyContinue | Out-Null
} catch {}

try {
    $StartupDir = [Environment]::GetFolderPath('Startup')
    if ($StartupDir -and (Test-Path $StartupDir)) {
        $StartupCmd = Join-Path $StartupDir "AntigravityPcBridge.cmd"
        $CmdContent = "@echo off`r`nstart `"`" powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$WatchdogScript`"`r`n"
        Set-Content -Path $StartupCmd -Value $CmdContent -Force -ErrorAction SilentlyContinue
    }
} catch {}

# 2. Check if watchdog process is already running
$watchdogAlive = $false
if (Test-Path $PidFile) {
    try {
        $savedPid = [int](Get-Content $PidFile -ErrorAction Stop | Select-Object -First 1)
        $p = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
        if ($p -and $p.ProcessName -match "powershell|pwsh") {
            $watchdogAlive = $true
        }
    } catch {}
}

if (-not $watchdogAlive) {
    try {
        Start-ScheduledTask -TaskName $TaskName -ErrorAction Stop
        Write-Host "[ensure-bridge] Started Scheduled Task $TaskName"
        Start-Sleep -Seconds 2
    } catch {
        $proc = Start-Process -FilePath "powershell.exe" -ArgumentList @("-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", "`"$WatchdogScript`"") -WorkingDirectory $BridgeDir -WindowStyle Hidden -PassThru
        Write-Host "[ensure-bridge] Started Watchdog process (PID=$($proc.Id))"
    }
} else {
    Write-Host "[ensure-bridge] Watchdog is already running (PID=$savedPid)"
}

# 3. Wait up to 10s for bridge health
for ($i = 0; $i -lt 10; $i++) {
    try {
        $h = Invoke-RestMethod -Uri "http://127.0.0.1:8787/api/health" -Method Get -TimeoutSec 2 -ErrorAction Stop
        if ($h.ok -eq $true) {
            Write-Host "[ensure-bridge] Bridge is ONLINE on port 8787"
            exit 0
        }
    } catch {}
    Start-Sleep -Seconds 1
}
Write-Host "[ensure-bridge] Warning: Bridge did not respond within 10s"
