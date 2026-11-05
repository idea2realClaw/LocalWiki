package com.idea2realclaw.qwenllm

/**
 * JNI 桥接层：对应 app/src/main/cpp/llm_bridge.cpp 中的三个 native 方法。
 * 类名/包名改变后，C++ 里的 JNI 函数名也要同步改。
 */
class LlamaWrapper {
    companion object {
        init {
            System.loadLibrary("llm_jni")
        }
    }

    /** 加载 GGUF 模型，返回句柄（0 = 失败）。默认 CPU；nGpuLayers=99 全部层卸载 GPU（需 Vulkan 1.2+ 设备）。 */
    external fun loadModel(path: String, nGpuLayers: Int = 0, nCtx: Int = 4096): Long

    /** 阻塞式生成，逐 token 通过 callback 回调。请在 Dispatchers.IO 中调用。 */
    external fun generate(handle: Long, prompt: String, callback: TokenCallback)

    /** 释放模型与上下文。 */
    external fun freeModel(handle: Long)
}

/** 流式生成回调。Kotlin 侧用 object : TokenCallback 实现。 */
interface TokenCallback {
    fun onToken(token: String)
    fun onDone()
}
