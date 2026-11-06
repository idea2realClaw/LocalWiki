package com.idea2realclaw.qwenllm.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 端侧工具调用解析器。
 *
 * 移植自 Zagent `src/qai/chat/adapters/openai_protocol.py`：
 * - `extract_xml_tool_call()` (:124)：`<tool_call>{"name":"read","arguments":{...}}</tool_call>`
 * - `sanitize_tool_call_name()` (:236)：名字限定 `[A-Za-z0-9_-]{1,64}`
 * - 容错 `arguments` / `parameters` 别名、arguments 内嵌整层调用对象时解包一层
 *
 * 端侧额外增强（小模型输出 JSON 不稳定，Zagent 云路径不需要）：
 * - 允许一条回复里多个 `<tool_call>` 块
 * - 去掉 ```json 代码围栏、截取首尾花括号
 * - 有开标签无闭标签时返回 [ParseResult.malformed]，让 Agent 回灌提示而不是静默当成最终答案
 *
 * 用 Android 框架自带的 org.json，零第三方依赖。
 */
object ToolCallParser {

    const val OPEN = "<tool_call>"
    const val CLOSE = "</tool_call>"

    private val NAME_ALLOWED = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun parse(text: String): ParseResult {
        if (!text.contains(OPEN)) return ParseResult()

        val calls = mutableListOf<ToolCall>()
        var malformed: String? = null
        var cursor = 0

        while (true) {
            val start = text.indexOf(OPEN, cursor)
            if (start < 0) break
            val contentStart = start + OPEN.length
            val end = text.indexOf(CLOSE, contentStart)
            if (end < 0) {
                malformed = "检测到 <tool_call> 但缺少 </tool_call> 结束标签。请重新输出完整的工具调用块。"
                break
            }
            val raw = text.substring(contentStart, end)
            val call = parseOne(raw)
            if (call == null) {
                malformed = ("工具调用 JSON 解析失败：「${raw.take(160)}」。请只输出合法 JSON，例如 " +
                        "{\"name\":\"read\",\"arguments\":{\"path\":\"a.md\"}}")
            } else {
                calls += call
            }
            cursor = end + CLOSE.length
        }
        return ParseResult(calls, malformed)
    }

    private fun parseOne(rawIn: String): ToolCall? {
        var raw = rawIn.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        val obj: JSONObject = try {
            JSONObject(raw)
        } catch (_: Exception) {
            // 容错：模型在 JSON 前后夹了说明文字时，抠出最外层对象。
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            try {
                JSONObject(raw.substring(a, b + 1))
            } catch (_: Exception) {
                return null
            }
        }

        val name = (obj.optString("name").ifBlank { obj.optString("tool") }).trim()
        if (!NAME_ALLOWED.matches(name)) return null

        var args = obj.optJSONObject("arguments")
            ?: obj.optJSONObject("parameters")
            ?: obj.optJSONObject("args")

        // 模型偶尔把整个调用对象又塞进 arguments 里，解包一层（对齐 Zagent）。
        if (args != null && args.has("name")) {
            args = args.optJSONObject("arguments") ?: args.optJSONObject("parameters") ?: args
        }

        return ToolCall(name, args?.toMap() ?: emptyMap(), raw)
    }

    /** 去掉所有工具调用块，用于生成最终展示文本。 */
    fun stripToolCalls(text: String): String {
        var out = text
        while (true) {
            val s = out.indexOf(OPEN)
            if (s < 0) break
            val e = out.indexOf(CLOSE, s + OPEN.length)
            out = if (e < 0) out.substring(0, s) else out.removeRange(s, e + CLOSE.length)
        }
        return out.trim()
    }

    /** 气泡里显示的紧凑参数摘要。 */
    fun prettyArgs(call: ToolCall): String =
        if (call.arguments.isEmpty()) "" else call.arguments.entries.joinToString(", ") { (k, v) ->
            val value = v?.toString() ?: "null"
            "$k=${if (value.length > 60) value.take(60) + "…" else value}"
        }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        keys().forEach { k ->
            when (val v = opt(k)) {
                is JSONObject -> map[k] = v.toMap()
                is JSONArray -> map[k] = (0 until v.length()).map { v.opt(it) }
                else -> map[k] = v
            }
        }
        return map
    }
}
