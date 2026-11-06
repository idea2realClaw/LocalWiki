package com.idea2realclaw.qwenllm.agent

import java.io.File

/**
 * 本地文件工具集：移植 Zagent `ai_coding/infrastructure/tools/handlers/_shared.py:224`
 * 的 read / list / write / glob / grep 五个工具语义（分页、截断提示、glob 优先定位）。
 *
 * 与 Zagent 的关键差异（端侧安全边界）：
 * - Zagent 的 FileGuard / native_file_guard 是 Windows DLL hook，Android 上不可用；
 *   这里改为**单一 workspace 根目录 + canonicalPath 前缀校验**，从源头堵住 `../` 穿越。
 * - 不移植 exec / webfetch / web_search：Android 无 shell 白名单语义，且离线 Agent 不需要联网。
 *   工具执行全部在 App 私有目录内，无需额外权限。
 * - 所有输出有硬上限，防止小模型上下文被大文件冲爆。
 */
class WorkspaceTools(rootDir: File) {

    private val root: File = rootDir.canonicalFile.also { it.mkdirs() }

    /** 工具主动拒绝/失败：必须回灌 ok=false，否则模型会误以为操作成功。 */
    private class ToolError(message: String) : IllegalArgumentException(message)
    private fun fail(message: String): Nothing = throw ToolError(message)

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "read",
            usage = "read(path, offset?, limit?)",
            description = "读取文本文件内容，返回带行号的片段；超长会截断并提示下一次的 offset。不要读二进制文件。",
            schemaJson = """{"type":"function","function":{"name":"read","description":"Read a TEXT file with line numbers.","parameters":{"type":"object","required":["path"],"properties":{"path":{"type":"string"},"offset":{"type":"number"},"limit":{"type":"number"}}}}}"""
        ),
        ToolSpec(
            name = "write",
            usage = "write(path, content)",
            description = "把 content 完整写入文件（覆盖），必要时创建父目录；只用于确实需要落盘的内容。",
            schemaJson = """{"type":"function","function":{"name":"write","description":"Write content to a file (complete overwrite).","parameters":{"type":"object","required":["path","content"],"properties":{"path":{"type":"string"},"content":{"type":"string"}}}}}"""
        ),
        ToolSpec(
            name = "list",
            usage = "list(path?)",
            description = "列出单层目录的直接子项（目录名带 / 后缀），排序并分页；不做递归。",
            schemaJson = """{"type":"function","function":{"name":"list","description":"List ONE directory level, non-recursive.","parameters":{"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"number"},"limit":{"type":"number"}}}}}"""
        ),
        ToolSpec(
            name = "glob",
            usage = "glob(pattern, cwd?)",
            description = "按通配符查文件，如 **/*.md、notes/*.txt；最多返回 100 条，按修改时间倒序。pattern 相对 workspace。",
            schemaJson = """{"type":"function","function":{"name":"glob","description":"Find files matching a glob pattern.","parameters":{"type":"object","required":["pattern"],"properties":{"pattern":{"type":"string"},"cwd":{"type":"string"}}}}}"""
        ),
        ToolSpec(
            name = "grep",
            usage = "grep(pattern, path?, include?, ignoreCase?)",
            description = "在文件内容里按正则搜索，返回 路径:行号:内容；最多 100 条命中。可先用它定位再 read。",
            schemaJson = """{"type":"function","function":{"name":"grep","description":"Search file contents by regex, returning path:line:text.","parameters":{"type":"object","required":["pattern"],"properties":{"pattern":{"type":"string"},"path":{"type":"string"},"include":{"type":"string"},"ignoreCase":{"type":"boolean"}}}}}"""
        )
    )

    /** 对齐 Zagent `_agentic_kernel.py` 的 OpenAI function schema 顺序，供未来 HTTP 通道复用。 */
    fun schemasJson(): String = specs.joinToString(prefix = "[", postfix = "]") { it.schemaJson }

    fun rootPath(): String = root.path

    fun invoke(call: ToolCall): ToolResult = try {
        when (call.name) {
            "read"  -> ok(call, read(call.arguments))
            "write" -> ok(call, write(call.arguments))
            "list"  -> ok(call, list(call.arguments))
            "glob"  -> ok(call, glob(call.arguments))
            "grep"  -> ok(call, grep(call.arguments))
            else -> ToolResult(
                call,
                "未知工具 '${call.name}'。可用工具：${specs.joinToString(", ") { it.name }}",
                false
            )
        }
    } catch (e: SecurityException) {
        ToolResult(call, "拒绝访问：${e.message}", false)
    } catch (e: ToolError) {
        ToolResult(call, e.message ?: "工具拒绝执行", false)
    } catch (e: Exception) {
        ToolResult(call, "工具执行异常 ${e::class.java.simpleName}: ${e.message}", false)
    }

    private fun ok(call: ToolCall, output: String) = ToolResult(call, output, true)

    // ---------------------------------------------------------------- 路径安全

    private fun resolve(raw: String): File {
        val f = if (raw.isBlank()) root else File(root, raw).canonicalFile
        val base = root.path
        if (f.path != base && !f.path.startsWith(base + File.separator)) {
            throw SecurityException("路径越出 workspace：$raw")
        }
        return f
    }

    /** 统一用 `/` 输出：glob/grep 的正则按 `/` 写，不依赖宿主机的路径分隔符。 */
    private fun rel(f: File): String = f.path
        .removePrefix(root.path)
        .replace(File.separatorChar, '/')
        .removePrefix("/")
        .ifBlank { "." }

    // ---------------------------------------------------------------- 工具实现

    private fun read(a: Map<String, Any?>): String {
        val f = resolve(a.str("path"))
        if (!f.exists()) fail("[read] 文件不存在：${rel(f)}")
        if (!f.isFile) fail("[read] 不是普通文件：${rel(f)}")
        if (f.length() > 2 * 1024 * 1024) fail("[read] 文件过大（${f.length()} B），请先用 grep 定位。")
        if (looksBinary(f)) fail("[read] 疑似二进制文件，已跳过（工具只支持文本）。")

        val lines = try { f.readLines() } catch (e: Exception) { fail("[read] 读取失败：${e.message}") }
        val offset = a.num("offset", 1).coerceAtLeast(1)
        val limit = a.num("limit", 300).coerceIn(1, 2000)
        val from = offset - 1
        if (from >= lines.size) fail("[read] ${rel(f)} 共 ${lines.size} 行，offset=$offset 已超出范围。")
        val end = minOf(lines.size, from + limit)

        return buildString {
            appendLine("[read] ${rel(f)}  第 $offset..$end 行 / 共 ${lines.size} 行")
            for (i in from until end) appendLine("${i + 1}: ${lines[i]}")
            if (end < lines.size) appendLine("…[已截断，继续请传 offset=${end + 1}]")
        }.let { AgentWire.truncate(it, 12000) }
    }

    private fun write(a: Map<String, Any?>): String {
        val f = resolve(a.str("path"))
        if (f.isDirectory) fail("[write] 目标是目录：${rel(f)}")
        val content = a.str("content")
        if (content.length > 64 * 1024) fail("[write] 内容超过 64K 字符，已拒绝（防止撑爆上下文）。")
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "[write] 已写入 ${rel(f)}（${content.length} 字符，${f.length()} 字节）"
    }

    private fun list(a: Map<String, Any?>): String {
        val f = resolve(a.str("path"))
        if (!f.isDirectory) fail("[list] 不是目录：${rel(f)}")
        val entries = f.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            ?: fail("[list] 无法读取目录：${rel(f)}")
        val offset = a.num("offset", 1).coerceAtLeast(1)
        val limit = a.num("limit", 200).coerceIn(1, 500)
        val from = offset - 1
        if (from >= entries.size && entries.isNotEmpty())
            fail("[list] ${rel(f)} 共 ${entries.size} 项，offset=$offset 已超出范围。")
        val end = minOf(entries.size, from + limit)
        return buildString {
            appendLine("[list] ${rel(f)}  $offset..$end / 共 ${entries.size} 项")
            for (i in from until end.coerceAtMost(entries.size)) {
                val e = entries[i]
                appendLine(if (e.isDirectory) "${e.name}/" else "${e.name}  (${e.length()} B)")
            }
            if (end < entries.size) appendLine("…[已截断，继续请传 offset=${end + 1}]")
        }
    }

    private fun glob(a: Map<String, Any?>): String {
        val pattern = a.str("pattern")
        if (pattern.isBlank()) fail("[glob] 缺少 pattern，例如 **/*.md")
        val cwd = resolve(a.str("cwd"))
        if (!cwd.isDirectory) fail("[glob] cwd 不是目录：${rel(cwd)}")
        val re = globToRegex(pattern)
        val found = cwd.walkTopDown()
            .maxDepth(8)
            .onEnter { it == cwd || (!it.name.startsWith(".") && it.name != ".git") }
            .filter { it.isFile && re.matches(rel(it)) }
            .sortedByDescending { it.lastModified() }
            .take(100)
            .toList()
        if (found.isEmpty()) return "[glob] 没有匹配 '$pattern' 的文件。"
        return buildString {
            appendLine("[glob] '$pattern' 命中 ${found.size} 个文件（按修改时间倒序）：")
            found.forEach { appendLine("${rel(it)}  (${it.length()} B)") }
        }
    }

    private fun grep(a: Map<String, Any?>): String {
        val pattern = a.str("pattern")
        val re = try {
            val opts = if (a.bool("ignoreCase", false)) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex(pattern, opts)
        } catch (e: Exception) {
            fail("[grep] 正则非法：${e.message}")
        }
        val target = resolve(a.str("path"))
        if (!target.exists()) fail("[grep] 路径不存在：${rel(target)}")
        val include = a.str("include")
        val includeRe = if (include.isBlank()) null else globToRegex(include)
        val candidates: Sequence<File> = if (target.isFile) sequenceOf(target)
        else target.walkTopDown()
            .maxDepth(8)
            .onEnter { it == target || (!it.name.startsWith(".") && it.name != ".git") }
            .filter {
                it.isFile && it.length() <= 1024 * 1024 &&
                        // `*.md` 按文件名匹配，`**/*.md` 按相对路径匹配（对齐 Zagent grep include 语义）
                        (includeRe == null || includeRe.matches(it.name) || includeRe.matches(rel(it)))
            }
            .take(300)

        var hits = 0
        val sb = StringBuilder()
        scan@ for (f in candidates) {
            if (looksBinary(f)) continue@scan
            val lines = try { f.readLines() } catch (_: Exception) { continue@scan }
            for ((idx, line) in lines.withIndex()) {
                if (re.containsMatchIn(line)) {
                    hits++
                    if (hits <= 100) sb.appendLine("${rel(f)}:${idx + 1}: ${line.take(300)}")
                }
            }
            if (hits > 100) break@scan
        }
        if (hits == 0) return "[grep] 没有匹配 '$pattern' 的内容。"
        return buildString {
            appendLine("[grep] '$pattern' 共 $hits 处命中${if (hits > 100) "（只列前 100 处）" else ""}：")
            append(sb)
        }
    }

    // ---------------------------------------------------------------- 辅助

    private fun looksBinary(f: File): Boolean {
        val buf = ByteArray(4096)
        val n = f.inputStream().use { it.read(buf) }
        return n > 0 && buf.take(n).any { it == 0.toByte() }
    }

    /** 双星后跟斜杠可匹配零层目录，单星不跨斜杠，问号匹配单字符。 */
    private fun globToRegex(p: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < p.length) {
            when {
                p[i] == '*' && i + 1 < p.length && p[i + 1] == '*' -> {
                    if (i + 2 < p.length && p[i + 2] == '/') {
                        sb.append("(?:.*/)?")
                        i += 3
                    } else {
                        sb.append(".*")
                        i += 2
                    }
                }
                p[i] == '*' -> { sb.append("[^/]*"); i++ }
                p[i] == '?' -> { sb.append("[^/]"); i++ }
                else -> { sb.append(Regex.escape(p[i].toString())); i++ }
            }
        }
        return Regex(sb.append("$").toString())
    }

    private fun Map<String, Any?>.str(key: String, default: String = ""): String =
        (this[key] as? String) ?: this[key]?.toString() ?: default

    private fun Map<String, Any?>.num(key: String, default: Int): Int = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: default
        else -> default
    }

    private fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean = when (val v = this[key]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> default
    }
}
