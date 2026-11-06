# LocalWiki / QwenAndroidLLM

Android 端 **本地离线 Agent**：Compose UI → Kotlin ReAct 循环 → JNI (`llm_bridge.cpp`) → llama.cpp（Vulkan GPU / CPU 后端）。
Agent 能力（工具调用协议 / Skills 思路 / 本地文件工具 / system prompt 分段）移植自 `Zagent`，详见 [第 9 节](#9-端侧-agent移植自-zagent)。
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
| Agent 协议/工具层回归（2026-11-06） | 本机 JVM | ✅ **24/24 PASS** | `tests/run-agent-selftest.ps1`，直接跑 Gradle 编译出的真实 `.class`；详见第 9.5 节 |
| Agent + GPU APK 本地编译（2026-11-06） | 本机 | ✅ | `BUILD SUCCESSFUL`，APK **70,884,725 B**，`libllm_jni.so` 80,640 B，含 `stopGenerate` 导出与 prefill/decode 计时日志 |

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
app/src/main/java/...        Compose UI / ViewModel(ReAct) / JNI 声明 / 模型路径
app/src/main/java/.../agent/ ToolModels / ToolCallParser / AgentWire / WorkspaceTools
app/src/main/cpp/            llm_bridge.cpp、CMakeLists.txt、llama.cpp(gitlink)
app/debug.keystore           统一 debug 签名（CI 与本地一致）
patches/                     ggml-vulkan 兼容性补丁
scripts/                     补丁应用、APK 构建脚本
gradle/wrapper/              Gradle 8.10.2 wrapper
cli_build/                   离线 llama-simple 交叉编译工程
tests/                       Agent 协议/工具层 JVM 回归（AgentSelfTest.java + ps1）
```

## 9. 端侧 Agent（移植自 Zagent）

> 2026-11-06 移植，形态为 **端侧 Kotlin 重写 + 最小闭环**。Zagent 的 Agent 是 PC 端
> Python 3.12 / FastAPI / asyncio 栈，无法原样搬进 Android；移植的是**协议与逻辑层**，
> QNN / Genie / Windows ARM64 / Model Pack runner / sub-agent / Web 工具全部丢弃。
> 端侧全程离线，不需要新增任何 Android 权限（工具只在 App 私有目录内工作）。

### 9.1 来源对照

| Zagent 源（均在 `tools/qaimodelbuilder/` 下） | LocalWiki 落地 | 移植内容 |
|---|---|---|
| `src/qai/chat/application/use_cases/_single_agent_turn.py:656` `SingleAgentTurnKernel.run()` | `ChatViewModel.runAgentLoop()` | build wire → 流式生成 → 分类 tool_calls → 执行 → 结果回灌 → 下一轮；maxRounds 上限 + abort |
| `.../_agentic_kernel.py:380/419/526` | `agent/AgentWire.kt` | wire 渲染、tool 结果块、结果截断 |
| `src/qai/chat/adapters/openai_protocol.py:124/236` | `agent/ToolCallParser.kt` | `<tool_call>{json}</tool_call>` 解析、`arguments\|parameters` 别名、嵌套解包、名字白名单 |
| `src/qai/ai_coding/infrastructure/tools/handlers/_shared.py:224` | `agent/ToolModels.kt`、`WorkspaceTools.specs` | OpenAI `{"type":"function","function":{...}}` schema 形状 |
| `.../handlers/read_write.py` / `search.py` | `agent/WorkspaceTools.kt` | read / write / list / glob / grep 语义（分页、截断提示、先定位后读） |
| `src/qai/security/adapters/native_file_guard.py` | `WorkspaceTools.resolve()` | Windows DLL hook → Android `canonicalPath` 前缀校验，堵 `../` 穿越 |
| `src/qai/chat/adapters/prompts/default_agent.txt` | `AgentWire.systemPrompt()` | identity / tool_use_philosophy / language_rule 分段 |
| `src/qai/platform/skills/discovery.py:155` | ⏳ 未移植 | SKILL.md front-matter + 无缓存重扫热重载（第二批） |

### 9.2 端侧工具调用协议

端侧 GGUF 没有 OpenAI `tools[]` 通道，所以工具清单压进 system prompt，模型用 Zagent
端侧兼容的 XML 文本协议输出调用（这正是 `openai_protocol.py:64` 的 `TOOL_CALL_OPEN`）：

```
<tool_call>{"name":"read","arguments":{"path":"notes.md"}}</tool_call>
```

`ToolCallParser` 容错：多个块、```json 代码围栏、JSON 前后夹说明文字、`tool`/`args` 别名、
`arguments` 内嵌整层调用对象；有开标签无闭标签时回灌格式错误提示让模型自纠，不静默失败。

**两处必要偏离**（避免以后误改）：
1. Zagent 云路径走 `payload["tools"]`，端侧改走 system prompt + 文本协议；`ToolSpec.schemaJson`
   仍保留 OpenAI function schema 形状，供未来接 HTTP 后端复用。
2. Zagent 用 `role:tool` 回灌结果；Qwen2.5 ChatML 未训练独立 tool 角色，这里统一以
   `user` + `<tool_result>` 标签回灌。

### 9.3 工具与安全边界

| 工具 | 参数 | 上限 |
|---|---|---|
| `read` | `path, offset?, limit?` | 2 MB 文件 / 2000 行 / 12K 字符输出，二进制跳过 |
| `write` | `path, content` | 64K 字符，完整覆盖 |
| `list` | `path?, offset?, limit?` | 单层，500 项 |
| `glob` | `pattern, cwd?` | 深度 8，100 条，按 mtime 倒序 |
| `grep` | `pattern, path?, include?, ignoreCase?` | 300 文件 / 100 命中 / 单文件 1 MB |

根目录固定为 `/Android/data/com.idea2realclaw.qwenllm/files/workspace/`（App 私有，免权限）。
每次访问做 `canonicalFile` + 前缀校验；`../../`、绝对路径、符号链接越界一律拒绝并返回错误。
不移植 `exec` / `webfetch` / `web_search`。

### 9.4 JNI 底座（为 Agent 补齐）

| 能力 | 改动 |
|---|---|
| 多轮上下文 | `generate()` 改收 Kotlin 拼好的完整 ChatML（system + 历史 + 工具结果），`parse_special=true`；每轮 `llama_memory_clear` 后全量 prefill |
| system prompt | 由 `AgentWire.systemPrompt()` 生成，含工具清单与协议 |
| 停止生成 | 新增 `stopGenerate(handle)`，`std::atomic<bool>` 在下一个 token 边界中断；UI 有「停止」按钮 |
| 上下文防溢出 | prompt 超 `n_ctx` 直接拒绝；生成到 `n_ctx-2` 自动停；wire **硬保证** ≤ 3000 字符（先丢中间历史，再逐条截断 content） |
| 性能可测 | logcat `QwenLlmJni` 每轮打印 `prefill: N tokens in X ms` 与 `decode: N tokens in X ms (Y t/s)` |
| 模型加载 | 从 UI 线程移到 `Dispatchers.IO`（避免 2 GB 加载 ANR）；`onCleared` 等生成线程退出后再 `freeModel`，避免 use-after-free |

> **取舍**：每轮全量重放历史（Agent 每轮 wire 都变，增量 KV 续接正确性差、实现复杂）。
> 代价是每轮重放，真实耗时由上面的 prefill 日志给出，**目前尚未在真机上测过具体数字**。

### 9.5 怎么用

```powershell
# 往 workspace 放几个测试文档（App 私有目录，adb 可直接写）
adb shell mkdir -p /sdcard/Android/data/com.idea2realclaw.qwenllm/files/workspace
adb push notes.md /sdcard/Android/data/com.idea2realclaw.qwenllm/files/workspace/notes.md

# 装 APK → 加载模型 → 试问：
#   "workspace 里有哪些 md 文件？挑一个总结内容"
#   "把刚才的总结写到 summary.md"
# 观察 logcat：
adb logcat -s QwenLlmJni
```

UI 会把每一轮的 assistant 输出、工具调用参数、工具结果分成独立气泡显示，状态栏显示当前轮次，
忙时可点「停止」，清空按钮重置对话历史。顶栏还有 **「自测」** 按钮：**不加载模型**即可在真机上
跑一遍协议解析 + 5 个工具 + 路径越界防护 + wire 构造，逐条输出 `[PASS]/[FAIL]`。

**离线回归（不需要设备、不需要模型）**：`tests/run-agent-selftest.ps1` 用 JDK 直接跑 Gradle
编译出的真实 Kotlin `.class`（`ToolCallParser` 依赖的 `org.json` 用官方实现在 JVM 上替代）：

```powershell
.\scripts\build-apk.ps1                 # 先产出 .class
.\tests\run-agent-selftest.ps1          # 退出码 0 = 全 PASS
```

2026-11-06 实测 **24/24 PASS**，覆盖：标准/多个/带代码围栏/`parameters` 别名/缺闭合标签/
JSON 夹文字的解析，未知工具、`../` 与绝对路径拒绝、write→read 分页→glob→grep→list 全链路、
64K 超限写入拒绝、ChatML 注入转义、wire 预算收敛（一条 5000 字输入从 6023 → 2070 字符）、
system/assistant 头结构、`tool_result` 回灌块。回归中暴露并修掉的 3 个真实缺陷：
Windows 路径分隔符导致 glob/grep 语义漂移、`include="*.md"` 未按文件名匹配、单条超长输入冲爆预算；
另外把工具「软拒绝」从 `ok=true` 改为 `ok=false`，避免模型误以为写入成功。

### 9.6 尚未验证 / 后续

- **未在一加 13 或任何真机上验证工具调用成功率**：3B Q4 模型输出结构化 JSON 的稳定性需实测，
  可能需要继续调 system prompt 或引入 grammar 约束采样。此处不提供任何模拟性能数据。
  真机可先点「自测」确认移植代码本身无误，再单独评估模型表现。
- 每轮全量 prefill 的耗时、多轮工具循环的总延迟，待真机 logcat 数据
  （`QwenLlmJni` 已打印每轮 `prefill: N tokens in X ms` / `decode: ... t/s`）。
- 第二批可接：SKILL.md 技能发现/热重载、工具并行执行（`_tool_round_executor.py`）、
  子代理、审计日志、策略热重载。
- 端侧目前无向量检索 / embedding，「LocalWiki」的知识库部分仍是下一步。
