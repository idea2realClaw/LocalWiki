package com.idea2realclaw.qwenllm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.idea2realclaw.qwenllm.agent.AgentWire
import com.idea2realclaw.qwenllm.agent.ChatTurn
import com.idea2realclaw.qwenllm.agent.ToolCall
import com.idea2realclaw.qwenllm.agent.ToolCallParser
import com.idea2realclaw.qwenllm.agent.ToolResult
import com.idea2realclaw.qwenllm.agent.WorkspaceTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** role: user / assistant / tool / status；[meta] 是气泡上方的轮次或工具名标签。 */
data class ChatMessage(val role: String, val content: String, val meta: String = "")

/**
 * Agent 主循环。
 *
 * 移植自 Zagent `src/qai/chat/application/use_cases/_single_agent_turn.py:656`
 * `SingleAgentTurnKernel.run()` 的结构：
 *   build_send_wire → 打开 LLM 流 → 分类帧 → 无 tool_calls 则结束
 *   → 有则执行工具 → 追加 role:tool 结果 → 下一轮（max_rounds 上限 + abort）。
 *
 * 端侧裁剪：流式帧只有 CHUNK（JNI 逐 token 回调），工具串行执行（Zagent 的
 * `_tool_round_executor.py` 并行版留作后续），SSE / DB / 子代理 / 快照全部去掉。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _status = MutableStateFlow("未加载模型")
    val status = _status.asStateFlow()

    private var handle: Long = 0
    private val wrapper = LlamaWrapper()

    /** App 私有目录，无需任何存储权限：/Android/data/<pkg>/files/workspace/；外部卷不可用时退回内部 filesDir。 */
    private val tools = WorkspaceTools(File(app.getExternalFilesDir(null) ?: app.filesDir, "workspace"))
    private val systemPrompt = AgentWire.systemPrompt(tools.specs)

    /** wire 历史，与 UI 消息分开：UI 有工具气泡/状态气泡，wire 只放 user/assistant 文本。 */
    private val turns = mutableListOf<ChatTurn>()

    @Volatile private var cancelled = false
    private val loading = AtomicBoolean(false)   // 防止加载期间双击并发载入两份 2GB 模型
    private var genJob: Job? = null
    private var genDone = CountDownLatch(0)

    fun loadModel(path: String) {
        if (handle != 0L || _isBusy.value || !loading.compareAndSet(false, true)) return
        _status.value = "正在加载模型…"
        // 2GB 模型加载放 IO，避免阻塞主线程触发 ANR（原实现直接在 UI 线程加载）。
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val h = wrapper.loadModel(path, nGpuLayers = BuildConfig.DEFAULT_GPU_LAYERS, nCtx = 4096)
                handle = h
                _status.value = if (h != 0L)
                    "模型已加载 · Agent 就绪（${tools.specs.size} 个本地工具）"
                else "模型加载失败，请确认 gguf 已放到 files/models/"
            } finally {
                loading.set(false)
            }
        }
    }

    fun send(prompt: String) {
        if (handle == 0L || prompt.isBlank() || _isBusy.value) return
        cancelled = false
        _isBusy.value = true
        turns += ChatTurn("user", prompt)
        addMessage(ChatMessage("user", prompt))

        genJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runAgentLoop()
            } finally {
                _isBusy.value = false
            }
        }
    }

    private fun runAgentLoop() {
        var round = 1
        while (round <= AgentWire.MAX_ROUNDS && !cancelled) {
            _status.value = "Agent 第 $round/${AgentWire.MAX_ROUNDS} 轮 · 生成中"
            val wire = AgentWire.build(systemPrompt, turns)
            val bubble = addMessage(ChatMessage("assistant", "", "第 $round 轮 · 生成中"))

            // ---- 打开 LLM 流（JNI 阻塞式逐 token 回调）----
            val out = StringBuilder()
            val cb = object : TokenCallback {
                override fun onToken(token: String) {
                    out.append(token)
                    replaceAt(bubble, ChatMessage("assistant", out.toString(), "第 $round 轮 · 生成中"))
                }
                override fun onDone() {}
            }
            genDone = CountDownLatch(1)
            try {
                wrapper.generate(handle, wire, cb)
            } catch (t: Throwable) {
                replaceAt(bubble, ChatMessage("status", "推理异常：${t.message}"))
                break
            } finally {
                genDone.countDown()
            }

            if (cancelled) {
                replaceAt(bubble, ChatMessage("status", "已停止生成（第 $round 轮）"))
                break
            }

            // ---- 分类：有没有 tool_calls ----
            val text = out.toString()
            val parsed = ToolCallParser.parse(text)
            if (parsed.calls.isEmpty() && parsed.malformed == null) {
                val answer = ToolCallParser.stripToolCalls(text).ifBlank { "（模型未输出内容）" }
                replaceAt(bubble, ChatMessage("assistant", answer, "第 $round 轮 · 完成"))
                turns += ChatTurn("assistant", answer)
                break
            }

            // ---- 中间轮：assistant 原文进 wire，气泡只显示去掉调用块后的文字 ----
            turns += ChatTurn("assistant", text)
            replaceAt(
                bubble,
                ChatMessage(
                    "assistant",
                    ToolCallParser.stripToolCalls(text).ifBlank { "（正在调用工具）" },
                    "第 $round 轮 · 调用工具"
                )
            )

            val results = mutableListOf<ToolResult>()
            val errors = mutableListOf<String>()
            parsed.malformed?.let {
                addMessage(ChatMessage("tool", it, "格式错误"))
                errors += it
            }
            for (call in parsed.calls.take(AgentWire.MAX_TOOL_CALLS_PER_ROUND)) {
                if (cancelled) break
                addMessage(ChatMessage("tool", "${call.name}(${ToolCallParser.prettyArgs(call)})", "第 $round 轮 · 调用"))
                val r = tools.invoke(call)
                addMessage(ChatMessage("tool", r.output.take(800), "结果 · ${if (r.ok) "OK" else "ERROR"}"))
                results += r
            }

            // ---- 工具结果回灌 wire，进入下一轮 ----
            val feedback = buildString {
                if (results.isNotEmpty()) append(AgentWire.toolResultBlock(results))
                if (errors.isNotEmpty()) {
                    if (length > 0) append('\n')
                    append(AgentWire.errorResult(errors.joinToString("\n")))
                }
            }
            turns += ChatTurn("user", feedback)
            round++
        }

        if (round > AgentWire.MAX_ROUNDS && !cancelled) {
            addMessage(ChatMessage("status", "已达到最大工具轮数 ${AgentWire.MAX_ROUNDS}，请缩小问题范围或清空对话重试。"))
        }
        _status.value = when {
            cancelled -> "已停止 · Agent 就绪"
            handle == 0L -> "未加载模型"
            else -> "Agent 就绪（${tools.specs.size} 个本地工具 · workspace）"
        }
    }

    fun stop() {
        if (!_isBusy.value) return
        cancelled = true
        if (handle != 0L) wrapper.stopGenerate(handle)   // 原子标志，JNI 在下一个 token 边界退出
        _status.value = "正在停止…"
    }

    fun clearChat() {
        if (_isBusy.value) return
        turns.clear()
        _messages.value = emptyList()
    }

    /**
     * 协议层 + 工具层回归自测，**不加载模型也能跑**。
     * 用于在真机上先排除移植代码的 bug，再单独评估 3B 模型是否会吐 `<tool_call>`。
     * 结果是真机实际执行输出，不是模拟数据。
     */
    fun selfTest() {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val report = try {
                runSelfTest()
            } catch (t: Throwable) {
                "自测异常：${t::class.java.simpleName}: ${t.message}"
            }
            addMessage(ChatMessage("tool", report, "自测结果"))
            _isBusy.value = false
        }
    }

    private fun runSelfTest(): String = buildString {
        appendLine("协议层 + 工具层自测（无需模型）")

        fun check(label: String, ok: Boolean, extra: String = "") {
            appendLine("${if (ok) "[PASS]" else "[FAIL]"} $label${if (extra.isBlank()) "" else " :: $extra"}")
        }

        val p1 = ToolCallParser.parse(
            "先查一下<tool_call>{\"name\":\"read\",\"arguments\":{\"path\":\"a.txt\"}}</tool_call>"
        )
        check(
            "标准 tool_call 解析",
            p1.calls.size == 1 && p1.calls[0].name == "read" && p1.calls[0].arguments["path"] == "a.txt"
        )

        val p2 = ToolCallParser.parse(
            "<tool_call>```json\n{\"name\":\"glob\",\"parameters\":{\"pattern\":\"**/*.md\"}}\n```</tool_call>"
        )
        check("代码围栏 + parameters 别名", p2.calls.size == 1 && p2.calls[0].arguments["pattern"] == "**/*.md")

        val p3 = ToolCallParser.parse("<tool_call>{\"name\":\"read\"")
        check("缺闭合标签 → malformed", p3.malformed != null)

        check("stripToolCalls", ToolCallParser.stripToolCalls("答<tool_call>{}</tool_call>案") == "答案")

        val unknown = tools.invoke(ToolCall("nope", emptyMap(), ""))
        check("未知工具返回 ERROR", !unknown.ok)

        val escape = tools.invoke(ToolCall("read", mapOf("path" to "../../etc/passwd"), ""))
        check("路径穿越被拒", !escape.ok, escape.output.take(60))

        val w = tools.invoke(
            ToolCall("write", mapOf("path" to "_selftest.md", "content" to "LocalWiki agent ok\nsecond line"), "")
        )
        check("write", w.ok, w.output.take(60))

        val rd = tools.invoke(ToolCall("read", mapOf("path" to "_selftest.md"), ""))
        check("read 带行号", rd.ok && rd.output.contains("1: LocalWiki agent ok"))

        val g = tools.invoke(ToolCall("grep", mapOf("pattern" to "agent ok", "include" to "*.md"), ""))
        check("grep 命中", g.ok && g.output.contains("_selftest.md:1"))

        val gl = tools.invoke(ToolCall("glob", mapOf("pattern" to "**/*.md"), ""))
        check("glob 命中", gl.ok && gl.output.contains("_selftest.md"))

        val li = tools.invoke(ToolCall("list", emptyMap(), ""))
        check("list 单层", li.ok && li.output.contains("_selftest.md"))

        val wire = AgentWire.build(systemPrompt, listOf(ChatTurn("user", "hi")))
        check(
            "wire 结构",
            wire.startsWith("<|im_start|>system") && wire.endsWith("<|im_start|>assistant\n"),
            "${wire.length} 字符"
        )

        appendLine("workspace: ${tools.rootPath()}")
        appendLine("以上为设备实跑结果；模型是否能产出 tool_call 需另做真机对话验证。")
    }

    private fun addMessage(msg: ChatMessage): Int {
        var idx = -1
        _messages.update { list -> (list + msg).also { idx = it.lastIndex } }
        return idx
    }

    private fun replaceAt(idx: Int, msg: ChatMessage) {
        if (idx < 0) return
        _messages.update { list ->
            if (idx in list.indices) list.toMutableList().also { it[idx] = msg } else list
        }
    }

    override fun onCleared() {
        stop()
        genJob?.cancel()
        // generate 是阻塞 JNI，cancel 协程不会中断它。等生成线程真正退出再 freeModel，
        // 否则会 use-after-free（原实现直接 freeModel 有这个隐患）。
        Thread({
            try { genDone.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            if (handle != 0L) {
                wrapper.freeModel(handle)
                handle = 0L
            }
        }, "localwiki-free-model").start()
        super.onCleared()
    }
}
