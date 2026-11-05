# LocalWiki / QwenAndroidLLM

Android 端 **llama.cpp GGUF 本地推理**示例：Jetpack Compose UI → JNI (`llm_bridge.cpp`) → llama.cpp（Vulkan GPU / CPU 后端）。
`llama.cpp` 以 git submodule 形式引入（上游 `ggml-org/llama.cpp`），仓库只维护 App 代码与一份兼容性补丁。

> **当前默认 GPU 版**（`nGpuLayers = 99`），目标设备为 **Vulkan 1.2+** 手机，如一加 13 / Adreno 830。
> Vulkan 1.1 设备（Samsung S22+ / Adreno 730）请用 `-PgpuLayers=0` 重编成 CPU 版。
> 模型 `qwen3.5-4b-q4_k_m.gguf`（实际为 Qwen2.5-3B-Instruct Q4_K_M，2,104,932,768 B）不含在仓库内。

## 0. 直接下载 CI 编好的 GPU APK

每次 push 到 `main`，GitHub Actions（`.github/workflows/build-gpu-apk.yml`）在 `windows-2022` 上完整编译 Vulkan GPU APK，并发布到 `gpu-latest` pre-release：

```
https://github.com/idea2realClaw/LocalWiki/releases/download/gpu-latest/QwenAndroidLLM-gpu.apk
```

也可在 [Actions 页面](https://github.com/idea2realClaw/LocalWiki/actions) 下载带 commit sha 的 artifact（保留 14 天）。
Release 里另有 `QwenAndroidLLM-gpu-<sha>.apk` 存档版。APK 用仓库内 `app/debug.keystore` 签名，与本地 debug 构建同签名，`adb install -r` 可直接覆盖。

手动触发：Actions → **Build GPU APK** → Run workflow，可指定 `gpu_layers`（99 / 0）与是否发布 Release。

> 首次出包实测（2026-11-06）：run `42128212416`，windows-2022 全程 **9m12s**，产物 **70,792,759 B**，内含 `libggml-vulkan.so` 42,411,912 B；直链 `curl -L` 返回 **HTTP 200**（无需登录）。Release 资产另存带 commit sha 的存档版。
> 复跑验证：run `42129541951` **全绿 6m44s**（含自动发布），`gpu-latest` 资产自动更新；公开仓库标准 runner 免费，冷构建约 7~9 分钟。

## 1. 实测状态

| 路径 | 设备 | 结果 | 关键数字 |
|---|---|---|---|
| CPU `-ngl 0 -n 32` | S22+ (Vulkan 1.1) | ✅ 跑通，正常文本，exit 0 | `32 tokens / 31.83 s = 1.01 t/s`；`eval 31819.06 ms / 32 runs (994.35 ms/token)`；`load 3927.16 ms`；`graph reallocs = 0` |
| GPU `-ngl 99` | S22+ (Vulkan 1.1) | ❌ abort 134 | `mul_mat_vec_q4_k_f32_f32` → `vk::Device::createComputePipeline: ErrorUnknown` |
| GPU `-ngl 99` | 一加 13 (Vulkan 1.3) | ⏳ 待师父真机验证 | 本仓库 CI 产物即为验证目标 |
| App 构建/安装/启动 | S22+ | ✅ | `BUILD SUCCESSFUL`，APK 70,792,759 B（内含 `libggml-vulkan.so` 42,411,928 B），`adb install -r` Success，MainActivity 完整生命周期无 `UnsatisfiedLinkError` / native 崩溃 |

**S22+ 为什么 GPU 不行**：Adreno 730 上报 `apiVersion = 1.1.128`，而 llama.cpp master 的 ggml-vulkan 使用 SPIR-V 1.5 (`0x00010500`) 量化 shader 且无条件依赖 Vulkan 1.2 核心入口点。设备虽支持 `VK_KHR_timeline_semaphore` / `VK_KHR_buffer_device_address` / `VK_KHR_vulkan_memory_model` 三个扩展，显式启用后量化 shader 仍在 1.1 驱动上编译失败 —— 属上游与设备的硬不兼容，非本仓库代码缺陷。

## 2. 环境要求

| 组件 | 版本（已验证） |
|---|---|
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.10.2（**仓库已含 wrapper**，用 `.\gradlew.bat`） |
| JDK | 17 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| Vulkan SDK | 1.4.309.0（提供 `vulkan.hpp` / `glslc` / SPIRV-Headers） |
| MSVC 2022 | 构建期 `vulkan-shaders-gen` 需主机 `cl.exe`，须在 VS DevShell 内执行 |
| minSdk / ABI | 26 / arm64-v8a |

Vulkan SDK 路径可用 `-PvulkanSdk=<path>` 或 `VULKAN_SDK` 环境变量覆盖，默认 `D:/VulkanSDK/1.4.309.0`（CI 装在 `C:\VulkanSDK\1.4.309.0`）。

## 3. 快速开始

```powershell
# 1) 克隆（连带 submodule）
git clone --recurse-submodules https://github.com/idea2realClaw/LocalWiki.git
cd LocalWiki

# 若已克隆但没带 submodule：
git submodule update --init --recursive

# 2) 打 vulkan 兼容补丁（幂等，可重复执行）
.\scripts\apply-llama-patch.ps1
# Linux/macOS: bash scripts/apply-llama-patch.sh

# 3) 把 GGUF 放到 App 外部目录
adb push qwen3.5-4b-q4_k_m.gguf /storage/emulated/0/Android/data/com.idea2realclaw.qwenllm/files/models/

# 4) 构建 APK（脚本进 VS DevShell 提供 cl.exe；默认 GPU 99 层）
.\scripts\build-apk.ps1

# 4b) 要 CPU 版：
.\scripts\build-apk.ps1 -GpuLayers 0

# 5) 安装
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

手工构建（等价于第 4 步）：在 **VS 2022 Developer PowerShell** 中执行

```powershell
.\gradlew.bat assembleDebug --console=plain -PgpuLayers=99
```

> `gradle.properties` 末尾 4 行是开发机的 `127.0.0.1:63354` 代理 + `android.sdk.dir` 绝对路径；**其他机器请删掉这几行**或改写到 `~/.gradle/gradle.properties`。CI 工作流会自动过滤它们，仓库文件保持不动，不影响本机构建。

## 4. GitHub Actions 做了什么

| 步骤 | 说明 |
|---|---|
| LF 行尾 | 全局关 `core.autocrlf`，保证 `git apply` 打补丁不失败 |
| checkout | `submodules: recursive` + `fetch-depth: 0`，取到 gitlink 锁定的 `3de52ac` |
| JDK / Gradle | `setup-java@v4` (17) + `setup-gradle@v4` 依赖缓存 |
| NDK | `sdkmanager --install ndk;27.2.12479018`（runner 预装版本不含它） |
| Vulkan SDK | 官方 installer 静默安装 `--accept-licenses --default-answer --confirm-command install copy_only=1` |
| SPIRV-Headers | 修正 `Lib/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake` 的 include 前缀 |
| 补丁 | `scripts/apply-llama-patch.ps1` |
| 构建 | VS DevShell（`cl.exe`）+ `gradlew assembleDebug -PgpuLayers=99` |
| 发布 | artifact + `gpu-latest` Release（公开直链，无需登录） |

首次 CI 实测踩过两个坑，均已修进工作流：

1. `Get-Content | ... | Set-Content` 指向同一文件会持有读句柄，Windows 报 `The process cannot access the file` → 先用 `@()` 缓冲到数组再写。
2. GitHub Releases API 偶发 **HTTP 500**（`POST /repos/.../releases`）→ 发布步骤改为最多 4 次重试，每轮重新判定 release 状态（create / edit）后再 `--clobber` 上传。

## 5. 补丁内容

`patches/ggml-vulkan-android-vulkan11.patch`（针对上游 `3de52ac`，仅改 `ggml/src/ggml-vulkan/ggml-vulkan.cpp`，+83/-2）：

| 修复 | 问题 |
|---|---|
| `VkPhysicalDeviceVulkan11/12Features` 零初始化 | 1.1 驱动不回填 1.2 特性结构，未初始化栈值被误判为「支持」 |
| `createDevice` 后 `init(device)` + 1.2 入口点全路径 resolve | Android loader 的 `vkGetInstanceProcAddr` 对 1.2 core 名返回 loader trampoline；`dladdr` 实测 KHR 名才落在 `/vendor/lib64/hw/vulkan.adreno.so` 真实实现 |
| BDA 保险 | 解析不到 `vkGetBufferDeviceAddress` 时关闭 BDA，避免 `getBufferAddress` 空指针段错误 |
| 1.1 设备显式启用 3 个 KHR 扩展 + 特性结构 | 补齐 1.1 设备的扩展启用路径 |

补丁按 `device->properties.apiVersion` 分支：Vulkan 1.2+ 设备走原生核心路径，不受影响；修复后 `getBufferAddress` / `waitSemaphores` 两处 SIGSEGV 均已消除。

## 6. 离线验证引擎（不经过 App）

`cli_build/` 提供独立 `llama-simple` 交叉编译脚本（用于绕开锁屏验证引擎本身）：

```powershell
cd cli_build
.\build_cli3.ps1          # 产物 build/bin/llama-simple
adb push build/bin/llama-simple /data/local/tmp/
adb shell run-as com.idea2realclaw.qwenllm cp /data/local/tmp/llama-simple /data/data/com.idea2realclaw.qwenllm/llama-simple
adb shell run-as com.idea2realclaw.qwenllm chmod 755 /data/data/com.idea2realclaw.qwenllm/llama-simple
adb shell "run-as com.idea2realclaw.qwenllm /data/data/com.idea2realclaw.qwenllm/llama-simple -m <模型路径> -ngl 99 -n 32 Hello"
```

`run-as` 的 UID 才能读 App 外部模型目录；`/data/local/tmp` 下的二进制不能直接 exec。

## 7. 已知限制

- GPU 路径在 Vulkan 1.1 设备上不可用（见第 1 节）；上 GPU 需 Vulkan 1.2+ 设备。
- 一加 13 的 GPU 推理尚未真机验证：加载模型 → 输入 → 发送，需观察 logcat 是否出现 `ggml_vulkan: Found 1 Vulkan devices`、GPU 层卸载日志与 token/s。
- 仓库不含模型文件；模型需自行下载并推送到设备。
- CI 产物是 debug 签名，不能上架应用商店；每次 Release 覆盖同一文件名，旧版在带 sha 的存档 APK 里。
- `app/debug.keystore` 是 Android 标准公开调试密钥（密码 `android`），**仅用于 debug 签名，切勿用作发布密钥**。

## 8. 目录结构

```
.github/workflows/           GPU APK CI 工作流
app/src/main/java/...        Compose UI / ViewModel / JNI 声明 / 模型路径
app/src/main/cpp/            llm_bridge.cpp、CMakeLists.txt、llama.cpp(gitlink)
app/debug.keystore           统一 debug 签名（CI 与本地一致）
patches/                     ggml-vulkan 兼容性补丁
scripts/                     补丁应用、APK 构建脚本
gradle/wrapper/              Gradle 8.10.2 wrapper
cli_build/                   离线 llama-simple 交叉编译工程
```
