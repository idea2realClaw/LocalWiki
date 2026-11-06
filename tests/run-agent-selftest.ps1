<#
.SYNOPSIS
    Agent 协议层 / 工具层离线回归：直接跑 Gradle 编译出的真实 Kotlin .class。

.DESCRIPTION
    不需要 Android 设备、不需要模型、不需要 Gradle daemon。
    用 JDK 的 javac/java + org.json 实现，在 JVM 上验证：
      - ToolCallParser 的 <tool_call> 解析与容错
      - WorkspaceTools 的 read/write/list/glob/grep 与路径越界防护
      - AgentWire 的 ChatML 结构、注入转义、上下文预算收敛
    先执行 .\scripts\build-apk.ps1（或任意 assembleDebug）生成 .class。
    退出码 0 = 全 PASS，非 0 = 有 FAIL。
#>
param([string] $KotlinVersion = "1.9.24")

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
# 让 PowerShell 控制台按 UTF-8 显示 Java 输出的中文，避免 GBK 乱码。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $PSScriptRoot ".cache"
New-Item -ItemType Directory -Force -Path $cache | Out-Null

$classes = Join-Path $repo "app/build/tmp/kotlin-classes/debug"
if (-not (Test-Path $classes)) {
    throw "找不到 Kotlin 编译产物，请先运行 .\scripts\build-apk.ps1：$classes"
}

$stdlibRoot = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib"
$stdlib = Get-ChildItem -Path $stdlibRoot -Recurse -Filter "kotlin-stdlib-$KotlinVersion.jar" `
    -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $stdlib) {
    throw "找不到 kotlin-stdlib-$KotlinVersion.jar（Gradle 缓存）：$stdlibRoot"
}

# ToolCallParser 用 Android 内置的 org.json；JVM 上用同一套 API 的官方实现替代。
$jsonJar = Join-Path $cache "org.json.jar"
if (-not (Test-Path $jsonJar)) {
    Write-Host "Downloading org.json ..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar" `
        -OutFile $jsonJar
}

$out = Join-Path $cache "classes"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$cp = "$classes;$($stdlib.FullName);$jsonJar"
& javac -encoding UTF-8 -nowarn -cp $cp -d $out (Join-Path $PSScriptRoot "AgentSelfTest.java")
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }

& java "-Dfile.encoding=UTF-8" -cp "$cp;$out" AgentSelfTest
if ($LASTEXITCODE -ne 0) { throw "Agent 回归存在 FAIL（见上方输出）" }

Write-Host "Agent regression: ALL PASS" -ForegroundColor Green
