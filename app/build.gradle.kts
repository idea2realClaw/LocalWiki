plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.idea2realclaw.qwenllm"
    compileSdk = 35

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
                    "-DCMAKE_PREFIX_PATH=D:/VulkanSDK/1.4.309.0",
                    // 直接指定 SPIRV-Headers 配置目录（绕过 Gradle/cmake 缓存的 NOTFOUND，
                    // Vulkan SDK 1.4.309.0 把 config 平铺在 Lib/cmake/ 下，已挪到 Lib/cmake/SPIRV-Headers/）
                    "-DSPIRV-Headers_DIR=D:/VulkanSDK/1.4.309.0/Lib/cmake/SPIRV-Headers",
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
