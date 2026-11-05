package com.idea2realclaw.qwenllm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val role: String, val content: String)

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _status = MutableStateFlow("未加载模型")
    val status = _status.asStateFlow()

    private var handle: Long = 0
    private val wrapper = LlamaWrapper()

    fun loadModel(path: String) {
        if (handle != 0L) return
        // S22+ (Adreno 730) 只支持 Vulkan 1.1，ggml master 的 Vulkan 后端要求 1.2
        // （SPIR-V 1.5 量化 shader 在该驱动上 createComputePipeline 失败并 abort）。
        // 因此默认走 CPU；换到 Vulkan 1.2+ 设备后可改回 99 启用 GPU 卸载。
        handle = wrapper.loadModel(path, nGpuLayers = 0, nCtx = 4096)
        _status.value = if (handle != 0L) "模型已加载 (ctx=4096)" else "模型加载失败"
    }

    fun send(prompt: String) {
        if (handle == 0L || prompt.isBlank() || _isBusy.value) return
        _isBusy.value = true
        _messages.update { it + ChatMessage("user", prompt) }
        _messages.update { it + ChatMessage("assistant", "") }
        val idx = _messages.value.lastIndex

        viewModelScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val cb = object : TokenCallback {
                override fun onToken(token: String) {
                    sb.append(token)
                    _messages.update { list ->
                        list.toMutableList().also { it[idx] = ChatMessage("assistant", sb.toString()) }
                    }
                }
                override fun onDone() {}
            }
            wrapper.generate(handle, prompt, cb)
            _isBusy.value = false
        }
    }

    override fun onCleared() {
        if (handle != 0L) {
            wrapper.freeModel(handle)
            handle = 0
        }
    }
}
