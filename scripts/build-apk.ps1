<#
.SYNOPSIS
    Build app-debug.apk with the Vulkan GPU backend enabled by default.

.DESCRIPTION
    ggml-vulkan generates shaders at build time with a host compiler, so MSVC's
    cl.exe must be on PATH. This script enters a VS 2022 DevShell when available.
    Uses the committed Gradle wrapper; falls back to a system Gradle 8.9+.

.PARAMETER GpuLayers
    nGpuLayers: 99 = offload every layer to Vulkan (default), 0 = CPU only.
    CPU builds are required on Vulkan 1.1 devices such as the S22+.

.PARAMETER VulkanSdk
    Override the Vulkan SDK root. Defaults to the VULKAN_SDK env var, then
    the D:/VulkanSDK/1.4.309.0 default baked into app/build.gradle.kts.
#>
param(
    [int]    $GpuLayers = 99,
    [string] $VulkanSdk = $env:VULKAN_SDK
)

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

$gradlew = Join-Path $repoRoot "gradlew.bat"
$gradle = $env:LOCALWIKI_GRADLE
if (-not $gradle) {
    $found = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if ($found) { $gradle = $found.Source }
}

$gradleArgs = @("assembleDebug", "--console=plain", "--no-daemon", "-PgpuLayers=$GpuLayers")
if ($VulkanSdk) { $gradleArgs += "-PvulkanSdk=$VulkanSdk" }

if (Test-Path $gradlew) {
    Write-Host "Using Gradle wrapper: $gradlew"
    & $gradlew @gradleArgs
} elseif ($gradle -and (Test-Path $gradle)) {
    Write-Host "Using system Gradle: $gradle"
    & $gradle @gradleArgs
} else {
    throw "Neither gradlew.bat nor Gradle 8.9+ was found. Set `$env:LOCALWIKI_GRADLE to a full gradle.bat path."
}
if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed with exit code $LASTEXITCODE" }

Write-Host "APK: $repoRoot\app\build\outputs\apk\debug\app-debug.apk (gpuLayers=$GpuLayers)" -ForegroundColor Green
