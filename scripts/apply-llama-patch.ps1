<#
.SYNOPSIS
    Apply the ggml-vulkan Vulkan 1.1 / Android compatibility patch to the
    llama.cpp submodule. Idempotent: safe to run more than once.
#>
$ErrorActionPreference = "Stop"

$repoRoot  = Split-Path -Parent $PSScriptRoot
$submodule = Join-Path $repoRoot "app\src\main\cpp\llama.cpp"
$patch     = Join-Path $repoRoot "patches\ggml-vulkan-android-vulkan11.patch"
$expected  = "3de52ac38818978d6da8f386eda43a856e53714a"

if (-not (Test-Path (Join-Path $submodule ".git"))) {
    throw "llama.cpp submodule is not initialized. Run: git submodule update --init --recursive"
}
if (-not (Test-Path $patch)) {
    throw "Patch file not found: $patch"
}

Push-Location $submodule
try {
    $head = (& git rev-parse HEAD).Trim()
    if ($head -ne $expected) {
        Write-Warning "submodule HEAD is $head, expected $expected - patch may not apply cleanly."
    }

    # Already applied -> reverse check succeeds -> nothing to do.
    & git apply --reverse --check $patch 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Patch already applied, nothing to do." -ForegroundColor Green
        return
    }

    & git apply --check $patch
    if ($LASTEXITCODE -ne 0) {
        throw "Patch does not apply cleanly to this submodule revision."
    }

    & git apply $patch
    if ($LASTEXITCODE -ne 0) {
        throw "git apply failed."
    }
    Write-Host "Patch applied to llama.cpp submodule." -ForegroundColor Green
}
finally {
    Pop-Location
}
