package com.idea2realclaw.qwenllm.agent

/**
 * 从 Zagent `tools/qaimodelbuilder/src/qai/ai_coding/infrastructure/tools/handlers/_shared.py:224`
 * 的 TOOL_SCHEMAS 移植过来的工具声明形状：`{"type":"function","function":{name,description,parameters}}`。
 *
 * 端侧 GGUF 模型没有 OpenAI `tools[]` 通道，所以 [schemaJson] 目前只用于协议对齐和未来
 * 接 HTTP 后端；实际喂给模型的是 [usage] + [description] 渲染出的紧凑工具清单。
 */
data class ToolSpec(
    val name: String,
    val usage: String,
    val description: String,
    val schemaJson: String
)

/** 一次工具调用，对齐 Zagent `openai_protocol.extract_xml_tool_call` 的返回值。 */
data class ToolCall(
    val name: String,
    val arguments: Map<String, Any?>,
    val raw: String
)

data class ToolResult(
    val call: ToolCall,
    val output: String,
    val ok: Boolean
)

/** wire 中的一轮。role: user / assistant / tool（tool 在拼 ChatML 时回灌为 user，见 AgentWire）。 */
data class ChatTurn(val role: String, val content: String)

/** 解析结果。[malformed] 非空表示模型输出了不完整的工具调用块，需回灌让模型自纠。 */
data class ParseResult(
    val calls: List<ToolCall> = emptyList(),
    val malformed: String? = null
)
