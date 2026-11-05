<#
.SYNOPSIS
    Build app-debug.apk.
    ggml-vulkan generates shaders at build time with a host compiler, so MSVC's
    cl.exe must be on PATH. This script enters a VS 2022 DevShell when available.

    Gradle 8.9+ is required. Put gradle.bat on PATH, or set:
        $env:LOCALWIKI_GRADLE = "C:\path\to\gradle-8.10.2\bin\gradle.bat"
#>
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$devShellDll = "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
if (Test-Path $devShellDll) {
    Import-Module $devShellDll
    Enter-VsDevShell -VsInstallPath "C:\Program Files\Microsoft Visual Studio\2022\Community" `
                     -DevCmdArguments "-arch=x64" | Out-Null
} else {
    Write-Warning "VS 2022 DevShell not found at the default path; assuming cl.exe is already on PATH."
}

$gradle = $env:LOCALWIKI_GRADLE
if (-not $gradle) {
    $found = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if ($found) { $gradle = $found.Source }
}
if (-not $gradle -or -not (Test-Path $gradle)) {
    throw "Gradle 8.9+ not found. Put gradle.bat on PATH or set `$env:LOCALWIKI_GRADLE to its full path."
}

Write-Host "Using Gradle: $gradle"
& $gradle assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed with exit code $LASTEXITCODE" }

Write-Host "APK: $repoRoot\app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
