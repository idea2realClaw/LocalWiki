# LocalWiki / QwenAndroidLLM

Android 端 **llama.cpp GGUF 本地推理**示例：Jetpack Compose UI → JNI (`llm_bridge.cpp`) → llama.cpp（CPU / Vulkan 后端）。
`llama.cpp` 以 git submodule 形式引入（上游 `ggml-org/llama.cpp`），仓库只维护 App 代码与一份兼容性补丁。

> 实测环境：Samsung S22+ (`SM-S906U1`)，2026-11-05。模型 `qwen3.5-4b-q4_k_m.gguf`（实际为 Qwen2.5-3B-Instruct Q4_K_M，2,104,932,768 B）。

## 1. 当前状态（实测数据）

| 路径 | 结果 | 关键数字 |
|---|---|---|
| CPU `-ngl 0 -n 32` | ✅ 跑通，输出正常文本，exit 0 | `32 tokens / 31.83 s = 1.01 t/s`；`eval 31819.06 ms / 32 runs (994.35 ms/token)`；`load 3927.16 ms`；`graph reallocs = 0` |
| GPU `-ngl 99` | ❌ abort 134 | `mul_mat_vec_q4_k_f32_f32` → `vk::Device::createComputePipeline: ErrorUnknown` |
| App | ✅ 构建/安装/启动 | `BUILD SUCCESSFUL in 29s`，APK 113,183,187 B，`adb install -r` Success，MainActivity 完整生命周期无 `UnsatisfiedLinkError` / native 崩溃 |

**为什么 GPU 不可用**：该机 Adreno 730 上报 `apiVersion = 1.1.128`，而 llama.cpp master 的 ggml-vulkan 使用 SPIR-V 1.5 (`0x00010500`) 量化 shader 且无条件依赖 Vulkan 1.2 核心入口点。设备虽支持 `VK_KHR_timeline_semaphore` / `VK_KHR_buffer_device_address` / `VK_KHR_vulkan_memory_model` 三个扩展，显式启用后量化 shader 仍在 1.1 驱动上编译失败 —— 属上游与设备的硬不兼容。
因此 `ChatViewModel.kt` / `LlamaWrapper.kt` 默认 `nGpuLayers = 0`。**改回 GPU 前请先确认设备 `apiVersion >= 1.2`。**

## 2. 环境要求

| 组件 | 版本（已验证） |
|---|---|
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.9+（实测 8.10.2；仓库**未包含** gradle wrapper） |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| Vulkan SDK | 1.4.309.0（提供 `vulkan.hpp`） |
| MSVC 2022 | 构建期 `vulkan-shaders-gen` 需主机 `cl.exe`，须在 VS DevShell 内执行 |
| minSdk / ABI | 26 / arm64-v8a |

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

# 4) 构建 APK（脚本会进 VS DevShell 提供 cl.exe）
.\scripts\build-apk.ps1

# 5) 安装
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

手工构建（等价于第 4 步）：在 **VS 2022 Developer PowerShell** 中执行

```powershell
gradle assembleDebug --console=plain
```

> `gradle.properties` 末尾 4 行是本机调试用的 `127.0.0.1:63354` 代理；**其他机器请删除这 4 行**或改写到 `~/.gradle/gradle.properties`。
> `app/src/main/cpp/CMakeLists.txt` 中 Vulkan SDK Include 路径硬编码为 `D:/VulkanSDK/1.4.309.0/Include`，换机器需同步修改。

## 4. 补丁内容

`patches/ggml-vulkan-android-vulkan11.patch`（针对上游 `3de52ac`，仅改 `ggml/src/ggml-vulkan/ggml-vulkan.cpp`，+83/-2）：

| 修复 | 问题 |
|---|---|
| `VkPhysicalDeviceVulkan11/12Features` 零初始化 | 1.1 驱动不回填 1.2 特性结构，未初始化栈值被误判为「支持」 |
| `createDevice` 后 `init(device)` + 1.2 入口点全路径 resolve | Android loader 的 `vkGetInstanceProcAddr` 对 1.2 core 名返回 loader trampoline；`dladdr` 实测 KHR 名才落在 `/vendor/lib64/hw/vulkan.adreno.so` 真实实现 |
| BDA 保险 | 解析不到 `vkGetBufferDeviceAddress` 时关闭 BDA，避免 `getBufferAddress` 空指针段错误 |
| 1.1 设备显式启用 3 个 KHR 扩展 + 特性结构 | 补齐 1.1 设备的扩展启用路径 |

修复后 `getBufferAddress` / `waitSemaphores` 两处 SIGSEGV 均已消除，Vulkan 后端初始化、异步上传、`sched_reserve` 建图（nodes=1233）全部正常。

## 5. 离线验证引擎（不经过 App）

`cli_build/` 提供独立 `llama-simple` 交叉编译脚本（用于绕开锁屏验证引擎本身）：

```powershell
cd cli_build
.\build_cli3.ps1          # 产物 build/bin/llama-simple
adb push build/bin/llama-simple /data/local/tmp/
adb shell run-as com.idea2realclaw.qwenllm cp /data/local/tmp/llama-simple /data/data/com.idea2realclaw.qwenllm/llama-simple
adb shell run-as com.idea2realclaw.qwenllm chmod 755 /data/data/com.idea2realclaw.qwenllm/llama-simple
adb shell "run-as com.idea2realclaw.qwenllm /data/data/com.idea2realclaw.qwenllm/llama-simple -m <模型路径> -ngl 0 -n 32 Hello"
```

`run-as` 的 UID 才能读 App 外部模型目录；`/data/local/tmp` 下的二进制不能直接 exec。

## 6. 已知限制

- GPU 路径在 Vulkan 1.1 设备上不可用（见第 1 节）；想上 GPU 需 Vulkan 1.2+ 设备，或把 shader 重编译到 SPIR-V 1.3 / 回退 llama.cpp 版本。
- App 的 UI 点击链路（加载模型 → 输入 → 发送）需在**已解锁**设备上人工验证；锁屏下 Activity 起来即被系统 pause/stop。
- 仓库不含 gradle wrapper 与模型文件；模型需自行下载并推送到设备。

## 7. 目录结构

```
app/src/main/java/...        Compose UI / ViewModel / JNI 声明 / 模型路径
app/src/main/cpp/            llm_bridge.cpp、CMakeLists.txt、llama.cpp(gitlink)
patches/                     ggml-vulkan 兼容性补丁
scripts/                     补丁应用、APK 构建脚本
cli_build/                   离线 llama-simple 交叉编译工程
```
