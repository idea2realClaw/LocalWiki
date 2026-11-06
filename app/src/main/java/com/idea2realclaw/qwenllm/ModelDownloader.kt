package com.idea2realclaw.qwenllm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** 用户主动取消：`.part` 保留，下次点击继续续传。 */
class DownloadCancelledException : Exception("下载已取消")

/** 下载源全部失败或文件校验不过。 */
class ModelDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 零第三方依赖的 GGUF 下载器，写入 App 私有 `files/models/`，免存储权限。
 *
 * 实测依据（2026-11-06，本机 curl）：
 * - HF 主源 `302 → cdn` 最终 `content-length: 2104932768`，`Range: bytes=0-15` → `206 / 16 B`；
 * - ModelScope `Range: bytes=0-15` → `206 / 16 B`，前 4 字节 `GGUF`；
 * - 两源 magic 一致，与设备上 `qwen3.5-4b-q4_k_m.gguf` 的 2,104,932,768 B 对齐。
 *
 * 设计：ModelScope（国内直连）→ HuggingFace → hf-mirror 逐源降级；
 * `.part` + `Range` 断点续传，完成后校验长度与 GGUF magic 再原子改名，
 * 避免半截文件被当成模型加载。
 */
object ModelDownloader {

    /** 保持与既有 adb push 路径 / README 一致的文件名。 */
    const val FILE_NAME = "qwen3.5-4b-q4_k_m.gguf"

    val SOURCES = listOf(
        "https://www.modelscope.cn/models/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
        "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
    )

    /** 小于 1 GiB 一律视为残缺（当前模型实测 2,104,932,768 B）。 */
    const val MIN_VALID_BYTES = 1024L * 1024 * 1024

    data class Progress(
        val bytes: Long,
        val total: Long,          // -1 = 服务器没给总长
        val sourceIndex: Int,     // 0 = 主源
        val resumed: Boolean
    )

    /** /Android/data/com.idea2realclaw.qwenllm/files/models/<FILE_NAME>；外部卷不可用时退回内部 filesDir。 */
    fun modelFile(context: Context): File =
        File(File(context.getExternalFilesDir(null) ?: context.filesDir, "models"), FILE_NAME)

    fun partFile(context: Context): File =
        File(modelFile(context).parentFile, "$FILE_NAME.part")

    /** 已存在的文件是否可直接加载：够大 + GGUF magic。 */
    fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_VALID_BYTES) return false
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 下载并返回最终模型文件。多源自动降级；取消时抛 [DownloadCancelledException]，
     * 部分内容留在 `.part` 供下次续传。[onProgress] 在 IO 线程回调。
     */
    suspend fun download(
        context: Context,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): File = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val target = modelFile(app)
        val part = partFile(app)
        target.parentFile?.mkdirs()

        val failures = mutableListOf<String>()
        for ((index, url) in SOURCES.withIndex()) {
            try {
                return@withContext fetch(url, part, target, index, onProgress, isCancelled)
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: Exception) {
                failures += "源${index + 1}: ${e.message ?: e::class.java.simpleName}"
            }
        }
        throw ModelDownloadException("全部下载源失败 → ${failures.joinToString(" | ")}")
    }

    private fun fetch(
        urlText: String,
        part: File,
        target: File,
        sourceIndex: Int,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val resumeAt = if (part.isFile) part.length() else 0L
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LocalWiki/0.1.0")
            if (resumeAt > 0) setRequestProperty("Range", "bytes=$resumeAt-")
        }
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw ModelDownloadException("HTTP $code")
            val resumed = resumeAt > 0 && code == 206
            val len = conn.getHeaderFieldLong("Content-Length", -1L)
            val total = parseTotal(conn.getHeaderField("Content-Range"), resumeAt, len, code)
            val start = if (resumed) resumeAt else 0L
            if (!resumed) part.delete()

            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    if (resumed) raf.seek(resumeAt) else raf.setLength(0)
                    val buf = ByteArray(64 * 1024)
                    var done = start
                    var lastEmit = start
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        // 每 4 MiB 刷新一次状态，避免高频重组 Compose
                        if (done - lastEmit >= 4L * 1024 * 1024 || (total > 0 && done >= total)) {
                            onProgress(Progress(done, total, sourceIndex, resumed))
                            lastEmit = done
                        }
                    }
                }
            }

            if (total > 0 && part.length() != total) {
                throw ModelDownloadException("文件不完整 ${part.length()}/$total B")
            }
            if (!isValid(part)) throw ModelDownloadException("GGUF 头校验失败或文件过小")
            if (target.exists() && !target.delete()) throw ModelDownloadException("旧模型文件无法删除")
            if (!part.renameTo(target)) throw ModelDownloadException("临时文件重命名失败")
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** `Content-Range: bytes 0-15/2104932768` → 总长；退化时用 resumeAt + Content-Length。 */
    private fun parseTotal(contentRange: String?, resumeAt: Long, len: Long, code: Int): Long {
        contentRange?.substringAfterLast('/')?.toLongOrNull()?.let { return it }
        return when {
            code == 206 && len > 0 -> resumeAt + len
            len > 0 -> len
            else -> -1L
        }
    }
}
