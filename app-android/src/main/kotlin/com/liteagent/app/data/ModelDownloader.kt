package com.liteagent.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 模型下载：断点续传 + SHA-256 校验。
 *
 * 为什么不用第三方下载库：这个需求只有「Range 请求 + 校验 + 进度回调」三件事，
 * 引一个几百 KB 的库不划算，而且 HttpURLConnection 在 Android 上足够稳。
 */
class ModelDownloader(private val store: ModelStore) {

    suspend fun download(
        entry: ModelEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!store.hasEnoughSpace(entry.totalBytes)) {
                error("存储空间不足，需要 ${entry.totalBytes / 1024 / 1024} MB")
            }

            val dir = store.modelDir(entry.id)
            if (!dir.exists() && !dir.mkdirs()) error("无法创建模型目录: ${dir.absolutePath}")

            // 先算一遍已有进度，让 UI 打开就是真实百分比而不是从 0 开始跳
            var baseDone = 0L
            for (mFile in entry.files) {
                val target = File(dir, mFile.name)
                onProgress(baseDone + minOf(target.length(), mFile.size), entry.totalBytes)

                if (target.isFile && target.length() == mFile.size) {
                    baseDone += mFile.size
                    continue
                }
                downloadOne(entry, mFile, target) { written ->
                    onProgress(baseDone + written, entry.totalBytes)
                }
                baseDone += mFile.size
            }

            if (!store.isReady(entry)) error("下载完成后完整性校验未通过")
        }
    }

    private fun downloadOne(
        entry: ModelEntry,
        mFile: ModelFile,
        target: File,
        onBytes: (Long) -> Unit
    ) {
        val part = File(target.parentFile, "${target.name}.part")

        // 已存在但字节数不对 → 视为脏文件。必须连 .part 一起删：
        // 否则下一次续传会从上一版模型残留的偏移开始拼，大小可能凑巧对上，白白下完才发现校验失败
        if (target.exists() && target.length() != mFile.size) {
            target.delete()
            part.delete()
        }

        var existing = if (part.exists()) part.length() else 0L
        if (existing > mFile.size) {
            part.delete()
            existing = 0
        }

        val url = URL("https://modelscope.cn/models/${entry.repoId}/resolve/master/${mFile.name}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiteAgent/0.1")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = conn.responseCode
            // 206 = 续传成功；200 = 服务端忽略 Range，只能从头写
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("下载 ${mFile.name} 失败，HTTP $code")
            }
            if (code == HttpURLConnection.HTTP_OK) {
                part.delete()
                existing = 0
            }

            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(existing)
                    val buffer = ByteArray(256 * 1024)
                    var written = existing
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                    out.fd.sync()
                }
            }

            if (part.length() != mFile.size) {
                throw IOException("${mFile.name} 大小不符：期望 ${mFile.size}，实际 ${part.length()}")
            }
            verifySha256(part, mFile.sha256)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun verifySha256(file: File, expected: String) {
        if (expected.isBlank()) return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw IOException("${file.name} 校验和不匹配，已删除损坏文件")
        }
    }
}
