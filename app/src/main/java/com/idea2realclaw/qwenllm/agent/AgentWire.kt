package com.idea2realclaw.qwenllm.agent

/**
 * wire 构造：移植 Zagent `src/qai/chat/application/use_cases/_agentic_kernel.py` 的
 * `build_assistant_tool_calls_block()` / `build_tool_reply_blocks()` / `truncate_tool_result()`
 * 与 `prompts/default_agent.txt` 的分段 system prompt 思路，裁剪到端侧最小闭环。
 *
 * 与 Zagent 的两点必要偏离（写在明处，避免以后误改）：
 * 1. Zagent 云路径走 `payload["tools"]`（OpenAI function-calling），端侧 GGUF 模型没有该通道，
 *    所以把工具清单压进 system prompt，并用 Zagent 端侧兼容的 `<tool_call>` 文本协议
 *    （`openai_protocol.py:64`）。
 * 2. Zagent 把工具结果以 `role:tool` 回灌；Qwen2.5 ChatML 未训练独立 tool 角色，
 *    这里统一回灌为 `user` 消息 + `<tool_result>` 标签，模型更容易稳定续接。
 */
object AgentWire {

    /** 对齐 Zagent `DEFAULT_SUB_AGENT_MAX_ROUNDS` 的思路，端侧 3B 模型收敛慢，给 6 轮。 */
    const val MAX_ROUNDS = 6
    const val MAX_TOOL_CALLS_PER_ROUND = 4

    /** 4096 ctx 下给 prompt 留的预算（中文约 1 字 ≈ 1 token），余量留给生成。 */
    private const val WIRE_BUDGET_CHARS = 3000
    private const val TOOL_RESULT_CHARS = 2200

    fun systemPrompt(specs: List<ToolSpec>): String = buildString {
        // === SECTION:identity ===
        appendLine("你是 LocalWiki，运行在 Android 手机上的本地离线 AI 助手，可以使用工具读写本机工作区文件。")
        appendLine()
        // === SECTION:tool_use_philosophy ===
        appendLine("## 可用工具（路径均相对于 workspace 目录）")
        specs.forEach { appendLine("- ${it.usage}：${it.description}") }
        appendLine()
        // === SECTION:tool protocol ===
        appendLine("## 工具调用协议")
        appendLine("需要查资料或改文件时，只输出工具调用块，不要夹带解释文字：")
        appendLine("<tool_call>{\"name\":\"read\",\"arguments\":{\"path\":\"notes.md\"}}</tool_call>")
        appendLine("可以一次输出多个块。参数必须是合法 JSON。")
        appendLine("工具结果会以下一条 user 消息的 <tool_result> 回灌给你，然后你继续：")
        appendLine("- 还需要工具：再输出 <tool_call> 块；")
        appendLine("- 已经够了：直接给出最终答案，绝对不要再出现 <tool_call> 标签。")
        appendLine("工具名或参数写错会收到错误信息，请修正后重试。")
        appendLine()
        // === SECTION:agent_principles ===
        appendLine("## 原则")
        appendLine("- 先 glob/grep 定位，再 read 精确读取，不要盲目遍历；引用文件用 路径:行号。")
        appendLine("- 不要复述大段工具输出，简要总结即可；不创建不必要的文件。")
        appendLine("- 若工具返回错误或被拒绝，如实说明，不要编造内容。")
        appendLine("- 工作区之外的路径一律禁止访问，不要尝试绕过。")
        // === SECTION:language_rule ===
        appendLine("- 始终使用与用户相同的语言回答（用户用中文就用中文）。")
    }

    /**
     * ChatML 全量 wire，硬保证不超预算（否则 4096 ctx 会被 prompt 顶爆）。
     * 1) 先丢中间历史，保住第一条（原始问题）与最后一条（最新上下文）；
     * 2) 仍超（例如用户一次粘贴几千字）就逐条截断 content。
     */
    fun build(system: String, turns: List<ChatTurn>): String {
        if (turns.isEmpty()) return render(system, emptyList())
        val kept = turns.toMutableList()
        var wire = render(system, kept)
        while (wire.length > WIRE_BUDGET_CHARS && kept.size > 2) {
            kept.removeAt(1)
            wire = render(system, kept)
        }
        if (wire.length > WIRE_BUDGET_CHARS) {
            val overhead = wire.length - kept.sumOf { it.content.length }
            val allowance = ((WIRE_BUDGET_CHARS - overhead) / kept.size).coerceAtLeast(200)
            for (i in kept.indices) {
                kept[i] = kept[i].copy(content = truncate(kept[i].content, allowance))
            }
            wire = render(system, kept)
        }
        return wire
    }

    /** 把一轮（或多轮并行）工具结果回灌为一条 user 消息，对齐 Zagent `build_tool_reply_blocks`。 */
    fun toolResultBlock(results: List<ToolResult>): String = buildString {
        appendLine("<tool_result>")
        results.forEach { r ->
            appendLine("[${if (r.ok) "OK" else "ERROR"}] ${r.call.name}")
            appendLine(truncate(r.output, TOOL_RESULT_CHARS))
        }
        appendLine("</tool_result>")
        append("请基于以上工具结果继续：还需要信息就再输出 <tool_call> 块；已经足够就直接给出最终答案（不要带 <tool_call> 标签）。")
    }

    fun errorResult(message: String): String = buildString {
        appendLine("<tool_result>")
        appendLine("[ERROR] format")
        appendLine(message)
        appendLine("</tool_result>")
        append("请修正工具调用格式后重新输出，或如果不需要工具就直接给出最终答案。")
    }

    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text
        else text.take(max) + "\n…[已截断，原文 ${text.length} 字符，只剩前 $max 字符；请用 grep / read offset 缩小范围]"

    private fun render(system: String, turns: List<ChatTurn>): String = buildString {
        append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        turns.forEach { t ->
            val role = if (t.role == "tool") "user" else t.role
            append("<|im_start|>").append(role).append("\n")
                .append(sanitize(t.content)).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    /** 防止用户输入/文件内容里的 ChatML 特殊 token 越出角色边界。 */
    private fun sanitize(content: String): String = content
        .replace("<|im_start|>", "<im_start>")
        .replace("<|im_end|>", "<im_end>")
}
