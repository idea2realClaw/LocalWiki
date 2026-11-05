package com.idea2realclaw.qwenllm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 零依赖的 GGUF 下载器：把模型流式写入 getExternalFilesDir/models/。
 * 也可用 adb push 直接把 .gguf 推到该目录，跳过运行时下载。
 */
object ModelDownloader {

    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }
        val out = File(dir, fileName)

        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connect() }
        val total = conn.contentLength
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(8192)
                var downloaded = 0
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) onProgress((downloaded * 100 / total))
                }
            }
        }
        conn.disconnect()
        out
    }

    /** 约定路径：/Android/data/com.idea2realclaw.qwenllm/files/models/<fileName> */
    fun modelFile(context: Context, fileName: String): File =
        File(File(context.getExternalFilesDir(null), "models"), fileName)
}
