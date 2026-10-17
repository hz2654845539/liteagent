package com.liteagent.app.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class DocMeta(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val chunkCount: Int = 0
)

/**
 * 本地文档库。
 *
 * 存储结构：`files/documents/<id>/content.txt` + 每条一个 meta 记录。
 * 为什么不用数据库：文档数量在几十篇量级，一个 JSON 索引足够，
 * 引 Room 会多 500KB 且要处理 schema 迁移，收益不成正比。
 */
class DocumentStore(context: android.content.Context) {

    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun listDocs(): List<DocMeta> = runCatching {
        if (!indexFile.exists()) emptyList()
        else json.decodeFromString(ListSerializer(DocMeta.serializer()), indexFile.readText())
    }.getOrElse { emptyList() }

    private fun saveIndex(docs: List<DocMeta>) {
        // 显式传 serializer：encodeToString 有多重载，靠类型推断会撞上
        // SerializationStrategy 那个版本，报 "No value passed for parameter 'value'"
        indexFile.writeText(json.encodeToString(ListSerializer(DocMeta.serializer()), docs))
    }

    /**
     * 导入一个文档。
     *
     * 只支持 txt / md。PDF 需要文本层提取，PdfRenderer 只能渲染不能取文本，
     * 得引 PdfBox-Android（约 3MB）——这个取舍留给后续版本，现在明确拒绝而不是假装支持。
     */
    suspend fun import(resolver: ContentResolver, uri: Uri): Result<DocMeta> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryDisplayName(resolver, uri) ?: "未命名文档"
                val ext = name.substringAfterLast('.', "").lowercase()
                require(ext in SUPPORTED) {
                    if (ext == "pdf") "暂不支持 PDF：需要文本层提取，计划引入 PdfBox 后再支持"
                    else "不支持的文件类型：$ext"
                }

                val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("无法读取文件")

                val content = normalize(text)
                require(content.isNotBlank()) { "文件内容为空" }

                val id = UUID.randomUUID().toString()
                val dir = File(root, id).apply { mkdirs() }
                File(dir, "content.txt").writeText(content)

                val chunks = com.liteagent.app.rag.TextChunker().chunk(id, name, content)
                val meta = DocMeta(
                    id = id,
                    name = name,
                    sizeBytes = content.toByteArray().size.toLong(),
                    importedAt = System.currentTimeMillis(),
                    chunkCount = chunks.size
                )
                saveIndex(listDocs() + meta)
                meta
            }
        }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        val remaining = listDocs().filterNot { it.id == id }
        saveIndex(remaining)
        true
    }

    fun readContent(id: String): String? = runCatching {
        File(File(root, id), "content.txt").readText()
    }.getOrNull()

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    /** 统一换行、去掉首尾空白，避免同一个文件在不同系统上切出不同块 */
    private fun normalize(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').trim()

    private companion object {
        val SUPPORTED = setOf("txt", "md", "markdown")
    }
}
