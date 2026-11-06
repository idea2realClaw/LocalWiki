<#
.SYNOPSIS
    ModelDownloader 离线回归：直接跑 Gradle 编译出的真实 Kotlin .class。

.DESCRIPTION
    不需要 Android 设备、不需要联网下载 2 GB 模型。
    用 JDK 内置 HttpServer 在本机起一个支持 Range 的假 GGUF 源，验证：
      - Content-Range 解析与长度回退
      - GGUF magic / 最小体积校验
      - 真实 206 断点续传 -> 完整性校验 -> 原子改名
      - 取消后保留 .part
    先执行 .\scripts\build-apk.ps1 生成 .class。退出码 0 = 全 PASS。
#>
param([string] $KotlinVersion = "1.9.24")

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
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

$out = Join-Path $cache "classes"
$stubOut = Join-Path $cache "stubs"
New-Item -ItemType Directory -Force -Path $out, $stubOut | Out-Null

# ModelDownloader 的公开方法签名带 android.content.Context，JVM 反射加载类时会解析它；
# 编一个空 stub 供 classpath 使用（被测的 parseTotal/isValid/fetch 完全不碰 Context）。
& javac -encoding UTF-8 -nowarn -d $stubOut (Join-Path $PSScriptRoot "stubs/android/content/Context.java")
if ($LASTEXITCODE -ne 0) { throw "Context stub 编译失败" }

$cp = "$classes;$($stdlib.FullName);$stubOut"
& javac -encoding UTF-8 -nowarn -cp $cp -d $out (Join-Path $PSScriptRoot "ModelDownloaderSelfTest.java")
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }

& java "-Dfile.encoding=UTF-8" -cp "$cp;$out" ModelDownloaderSelfTest
if ($LASTEXITCODE -ne 0) { throw "Downloader 回归存在 FAIL（见上方输出）" }

Write-Host "Downloader regression: ALL PASS" -ForegroundColor Green
