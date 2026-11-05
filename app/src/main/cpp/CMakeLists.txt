cmake_minimum_required(VERSION 3.22.1)
project(qwen_llm CXX)

# llama.cpp 作为 git submodule 放在 cpp/llama.cpp
add_subdirectory(llama.cpp)

# 交叉编译 Android 时，find_package(Vulkan) 会找到 NDK 的 vulkan.h（NDK sysroot 不含
# C++ 包装头 vulkan.hpp），导致 ggml-vulkan 的 #include <vulkan/vulkan.hpp> 失败。
# 显式补 Vulkan SDK 的 Include 目录（含 vulkan.hpp），只对 ggml-vulkan 生效。
# 同时交叉编译时 Vulkan::Vulkan 的链接属性未正确传递 libvulkan.so，需显式链接 Vulkan
# loader（提供 vkGetPhysicalDeviceFeatures2 等 Vulkan 1.1+ 符号），否则链接报 undefined symbol。
if (ANDROID AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE D:/VulkanSDK/1.4.309.0/Include)
    target_link_libraries(ggml-vulkan PRIVATE vulkan)
    # Android 的 NDK libvulkan.so 桩（≤API27）只导出 Vulkan 1.0 符号，而 ggml-vulkan 直接
    # 静态调用 Vulkan 1.1+ 函数（如 vkGetPhysicalDeviceFeatures2）。这些符号由设备系统的
    # libvulkan.so（完整 loader）在运行时提供，故链接时允许未定义符号（覆盖 NDK 默认的 --no-undefined）。
    target_link_options(ggml-vulkan PRIVATE "-Wl,--unresolved-symbols=ignore-all")
endif()

add_library(llm_jni SHARED llm_bridge.cpp)

target_include_directories(llm_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include)

target_link_libraries(llm_jni
        llama
        ggml
        android
        log)
