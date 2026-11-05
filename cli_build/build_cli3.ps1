$ErrorActionPreference = "Continue"

# 注入 MSVC 主机环境（vulkan-shaders-gen 主机工具需要 cl.exe）
$vsPath = "C:\Program Files\Microsoft Visual Studio\2022\Community"
Import-Module "$vsPath\Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
Enter-VsDevShell -VsInstallPath $vsPath -DevCmdArguments "-arch=x64"

$env:ANDROID_SDK_ROOT = "C:\Users\zhuxi\AppData\Local\Android\Sdk"
$env:VULKAN_SDK        = "D:\VulkanSDK\1.4.309.0"
$env:PATH             = "$env:ANDROID_SDK_ROOT\cmake\3.22.1\bin;" + $env:PATH

$ndk = "$env:ANDROID_SDK_ROOT\ndk\27.2.12479018"
Set-Location "D:\DiskD\GitHub\LocalWiki\cli_build"
$buildDir = "build"

# ---- 在已有 Ninja build 目录上增量 reconfigure（不删目录，避免安全删除拦截）----
# 关键：显式打开 LLAMA_BUILD_COMMON=ON，否则顶层 if(COMMON AND EXAMPLES) 不添加 examples，
# 就不会生成 llama-simple 目标。
# 另：NDK 不同 API 级的 libvulkan.so 桩不同（24-27 缺 Vulkan 1.1+ 符号，28+ 才有），
# 必须显式指定 API 28 的桩覆盖缓存里旧有的 26 桩路径，否则仍会链接到 26 桩导致
# vkGetPhysicalDeviceFeatures2 等 1.1+ 符号未定义（运行时空指针→段错误）。
$api28Vulkan = "C:/Users/zhuxi/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so"
$configure = @(
    "-G", "Ninja",
    "-S", ".",
    "-B", $buildDir,
    "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
    "-DANDROID_ABI=arm64-v8a",
    "-DANDROID_PLATFORM=android-28",
    "-DANDROID_STL=c++_static",
    "-DGGML_VULKAN=ON",
    "-DGGML_OPENMP=OFF",
    "-DLLAMA_BUILD_TESTS=OFF",
    "-DGGML_BUILD_TESTS=OFF",
    "-DLLAMA_BUILD_COMMON=ON",
    "-DLLAMA_BUILD_EXAMPLES=ON",
    "-DBUILD_SHARED_LIBS=OFF",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DVulkan_LIBRARY=$api28Vulkan",
    "-DCMAKE_PREFIX_PATH=D:/VulkanSDK/1.4.309.0",
    "-DSPIRV-Headers_DIR=D:/VulkanSDK/1.4.309.0/Lib/cmake/SPIRV-Headers"
)
Write-Host "===== cmake reconfigure (add LLAMA_BUILD_COMMON=ON) at $(Get-Date) ====="
# 清掉旧缓存，强制重新探测 Vulkan 桩（避免命中 26 桩的缓存值）
if (Test-Path "$buildDir/CMakeCache.txt") { Remove-Item -Force "$buildDir/CMakeCache.txt" }
cmake @configure *> configure.log
Write-Host "CONFIGURE_EXIT=$LASTEXITCODE"

Write-Host "===== cmake build : llama-simple (Release) ====="
cmake --build $buildDir --target llama-simple --config Release *> build.log
Write-Host "BUILD_EXIT=$LASTEXITCODE"
Write-Host "DONE at $(Get-Date)"
