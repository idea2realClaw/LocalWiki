cmake_minimum_required(VERSION 3.22.1)
project(qwen_cli_verifier C CXX)

# 直接复用 app 内已验证可用的 llama.cpp（master 分支，含 Vulkan 后端）。
# 注意：必须在 Android 交叉编译 + MSVC 主机环境(DevShell)下 configure，
# 这样 vulkan-shaders-gen 主机工具会用 cl.exe 而非 NDK clang 编译。
add_subdirectory(../app/src/main/cpp/llama.cpp llama.cpp_build)

# 复刻 app 的 ggml-vulkan 补丁（#15 攻克点）：
#  交叉编译 find_package(Vulkan) 命中 NDK 头（无 vulkan.hpp C++ 包装头），
#  需补 Vulkan SDK 完整 Include 目录。
if (ANDROID AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE D:/VulkanSDK/1.4.309.0/Include)
    target_link_libraries(ggml-vulkan PRIVATE vulkan)
endif()

# 关键修复（验证工具 + app 通用）：NDK API<=27 的 libvulkan.so 桩缺 Vulkan 1.1+ 符号，
# ggml-vulkan 通过 vkGetInstanceProcAddr(实例=1.0) 取这些函数会得到 NULL 指针 → 段错误。
# 解决方案：构建平台升到 android-28+（NDK 28+ 桩提供 vkGetPhysicalDeviceFeatures2 等 thunk，
# 运行时由设备 loader 分发），无需 ignore 未解析符号。本构建统一用 android-28。
