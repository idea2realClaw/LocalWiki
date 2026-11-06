plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Vulkan SDK root. Override with -PvulkanSdk=<path> or the VULKAN_SDK env var.
// GitHub Actions installs the SDK and sets VULKAN_SDK explicitly.
val vulkanSdk: String = (project.findProperty("vulkanSdk") as? String)
    ?.replace('\\', '/')?.trimEnd('/')
    ?: System.getenv("VULKAN_SDK")?.replace('\\', '/')?.trimEnd('/')
    ?: "D:/VulkanSDK/1.4.309.0"

// 0 = pure CPU, 99 = offload every layer to the Vulkan GPU backend.
// Override per build with -PgpuLayers=<n> (CI passes 99 explicitly).
val gpuLayers: Int = (project.findProperty("gpuLayers") as? String)?.toIntOrNull() ?: 99

// App window height in *physical pixels* (not dp). Override with -PwindowHeightPx=<n>.
val windowHeightPx: Int = (project.findProperty("windowHeightPx") as? String)?.toIntOrNull() ?: 900

android {
    namespace = "com.idea2realclaw.qwenllm"
    compileSdk = 35

    // Use the standard Android debug keystore committed to the repo so CI builds
    // and local builds share one signature: `adb install -r` works without
    // uninstalling. This is the public debug key only, never a release key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.idea2realclaw.qwenllm"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndkVersion = "27.2.12479018"

        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("int", "DEFAULT_GPU_LAYERS", gpuLayers.toString())
        buildConfigField("int", "WINDOW_HEIGHT_PX", windowHeightPx.toString())

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // GGML_VULKAN=ON 开启 Vulkan GPU 后端；要 NPU 改成 -DGGML_QNN=ON（需 Qualcomm AI SDK）
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_VULKAN=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    // 显式指明 Vulkan SDK 根目录，使 find_package(Vulkan) 能定位 glslc
                    "-DCMAKE_PREFIX_PATH=$vulkanSdk",
                    // 直接指定 SPIRV-Headers 配置目录（绕过 Gradle/cmake 缓存的 NOTFOUND）
                    "-DSPIRV-Headers_DIR=$vulkanSdk/Lib/cmake/SPIRV-Headers",
                    // 传给 native CMakeLists，用于补 vulkan.hpp 头文件路径
                    "-DVULKAN_SDK_ROOT=$vulkanSdk",
                    // 哨兵变量：仅用于改变 Gradle 配置 hash，强制重新 configure。
                    // 使主机端 vulkan-shaders-gen 在 MSVC/cl.exe 环境下重新检测编译器，
                    // 而非复用上次 NDK clang 的缓存 host-toolchain.cmake。
                    "-DFORCE_MSVC_HOST_RERUN=1"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
