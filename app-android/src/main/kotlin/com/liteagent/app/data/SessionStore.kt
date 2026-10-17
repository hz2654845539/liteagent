package com.liteagent.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.liteagent.shared.ChatMessage
import com.liteagent.shared.ChatRole
import java.io.File
import java.util.UUID

/** 会话元信息，用于列表展示。正文单独存，列表不用读全部消息。 */
@Serializable
data class SessionMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)

@Serializable
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val presetId: String = "assistant",
    val messages: List<ChatMessage> = emptyList(),
    /**
     * 早期对话的摘要。
     *
     * 历史按字符预算截断时，超出的部分会被直接丢掉——用户问"第一轮聊了什么"就答不上来。
     * 摘要用一句话留住事实，比丢掉强，也比留全文便宜（一句话约 30 token，一整轮约 200）。
     */
    val summary: String = "",
    /** 前 N 条消息已经被 [summary] 覆盖，发模型时跳过，但界面上照常显示 */
    val summarizedCount: Int = 0
)

/**
 * 会话存储。
 *
 * 每个会话一个 JSON 文件（`files/sessions/<id>.json`），外加一个索引文件。
 * 不上数据库的理由：会话数量在几十量级，一个 JSON 足够；
 * 而且导出单条会话时直接复制那个文件就行，不用写查询。
 */
class SessionStore(context: Context) {

    private val root = File(context.filesDir, "sessions").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 索引文件的读写在多个协程里发生（生成结束保存、切换会话保存、删除会话）。
     * 没有锁的话，"读索引 → 改 → 写回"这个序列会互相覆盖，最坏情况是索引里丢会话
     * 或者留下指向不存在文件的条目。会话数量小，全量互斥的代价可以忽略。
     */
    private val lock = Any()

    fun list(): List<SessionMeta> = synchronized(lock) {
        runCatching {
            if (!indexFile.exists()) emptyList()
            else json.decodeFromString(ListSerializer(SessionMeta.serializer()), indexFile.readText())
        }.getOrElse { emptyList() }.sortedByDescending { it.updatedAt }
    }

    fun create(presetId: String = "assistant"): Session {
        val now = System.currentTimeMillis()
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            createdAt = now,
            updatedAt = now,
            presetId = presetId
        )
        save(session)
        return session
    }

    fun load(id: String): Session? = runCatching {
        val file = File(root, "$id.json")
        if (!file.exists()) null
        else json.decodeFromString(Session.serializer(), file.readText())
    }.getOrNull()

    /**
     * 保存会话并同步索引。
     *
     * 标题从第一条用户消息里取——比让用户手动命名省事，
     * 也比"会话 1 / 会话 2"有用得多。
     */
    fun save(session: Session) = synchronized(lock) {
        val firstUser = session.messages.firstOrNull { it.role == ChatRole.USER }?.content
        val title = firstUser?.take(20)?.replace("\n", " ")?.takeIf { it.isNotBlank() }
            ?: session.title

        val normalized = session.copy(title = title, updatedAt = System.currentTimeMillis())
        File(root, "${session.id}.json").writeText(
            json.encodeToString(Session.serializer(), normalized)
        )

        val metas = list().filterNot { it.id == session.id } + SessionMeta(
            id = normalized.id,
            title = normalized.title,
            updatedAt = normalized.updatedAt,
            messageCount = normalized.messages.size
        )
        indexFile.writeText(json.encodeToString(ListSerializer(SessionMeta.serializer()), metas))
    }

    fun delete(id: String) = synchronized(lock) {
        File(root, "$id.json").delete()
        val remaining = list().filterNot { it.id == id }
        indexFile.writeText(json.encodeToString(ListSerializer(SessionMeta.serializer()), remaining))
    }

    /** 导出为 Markdown，用于分享或贴进文档 */
    fun exportMarkdown(session: Session): String = buildString {
        appendLine("# ${session.title}")
        appendLine()
        appendLine("> ${session.messages.size} 条消息 · 本地模型生成")
        appendLine()
        session.messages.forEach { m ->
            val speaker = when (m.role) {
                ChatRole.USER -> "我"
                ChatRole.ASSISTANT -> "LiteAgent"
                ChatRole.SYSTEM -> "系统"
                ChatRole.TOOL -> "工具"
            }
            appendLine("**$speaker**")
            appendLine()
            appendLine(m.content)
            if (m.citations.isNotEmpty()) {
                appendLine()
                appendLine("> 来源：" + m.citations.joinToString("  ") { "《${it.docName}》" })
            }
            appendLine()
        }
    }
}
