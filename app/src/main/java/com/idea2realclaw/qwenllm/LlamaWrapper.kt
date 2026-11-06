package com.idea2realclaw.qwenllm

/**
 * JNI 桥接层：对应 app/src/main/cpp/llm_bridge.cpp 中的 native 方法。
 * 类名/包名改变后，C++ 里的 JNI 函数名也要同步改。
 */
class LlamaWrapper {
    companion object {
        init {
            System.loadLibrary("llm_jni")
        }
    }

    /** 加载 GGUF 模型，返回句柄（0 = 失败）。nGpuLayers=99 全部层卸载 GPU（需 Vulkan 1.2+ 设备），0 为纯 CPU。 */
    external fun loadModel(path: String, nGpuLayers: Int = 99, nCtx: Int = 4096): Long

    /**
     * 阻塞式生成，逐 token 通过 callback 回调。请在 Dispatchers.IO 中调用。
     * [fullPrompt] 必须是拼好的完整 ChatML 对话（含 system / 历史 / 工具结果，末尾为 `<|im_start|>assistant\n`），
     * 见 [com.idea2realclaw.qwenllm.agent.AgentWire]。C++ 侧会清空 KV cache 后全量 prefill。
     */
    external fun generate(handle: Long, fullPrompt: String, callback: TokenCallback)

    /** 请求中断当前生成（线程安全，在下一个 token 边界生效）。 */
    external fun stopGenerate(handle: Long)

    /** 释放模型与上下文。调用前必须先 stopGenerate 并等待生成协程退出。 */
    external fun freeModel(handle: Long)
}

/** 流式生成回调。Kotlin 侧用 object : TokenCallback 实现。 */
interface TokenCallback {
    fun onToken(token: String)
    fun onDone()
}
