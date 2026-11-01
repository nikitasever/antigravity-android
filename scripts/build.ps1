param(
    [string]$Task = "compileDebugKotlin"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

$env:JAVA_HOME = "C:\Users\st811210\.gemini\antigravity\scratch\jdk17\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\st811210\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host "[build.ps1] Running .\gradlew.bat $Task --console=plain"
& "$ProjectRoot\gradlew.bat" $Task --console=plain
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
