package com.liteagent.app.rag

import com.liteagent.shared.Citation

data class RagHit(val rank: Int, val chunk: Chunk, val score: Float)

/**
 * 检索增强生成。
 *
 * 只做两件事：从本地文档里找出相关片段、拼成带编号的 prompt。
 * 它不负责生成——生成仍走 LlmEngine，这样 RAG 可以独立测试，也能随时换成向量检索。
 */
class RagEngine(private val topK: Int = 3) {

    private val bm25 = Bm25Index()

    fun rebuild(chunks: List<Chunk>) {
        bm25.clear()
        bm25.addAll(chunks)
    }

    suspend fun retrieve(question: String): List<RagHit> =
        bm25.search(question, topK).mapIndexed { i, scored ->
            RagHit(rank = i + 1, chunk = scored.chunk, score = scored.score)
        }

    /**
     * 拼 prompt。
     *
     * 三个刻意的约束，都是针对小模型的：
     * 1. 用编号 [1][2] 而不是让模型复述文件名——小模型抄长文件名会错字；
     * 2. 明确要求"资料里没有就说没有"，否则 1.5B 会开始编；
     * 3. 只放 topK 段，宁可召回不全也不要让上下文膨胀（prefill 成本在手机上是真实开销）。
     */
    fun buildPrompt(question: String, hits: List<RagHit>): String {
        if (hits.isEmpty()) return question

        return buildString {
            appendLine("以下是用户设备里的资料片段：")
            appendLine()
            hits.forEach { hit ->
                val heading = hit.chunk.headingPath.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                appendLine("[${hit.rank}] 《${hit.chunk.docName}》$heading")
                appendLine(hit.chunk.text.trim())
                appendLine()
            }
            appendLine("问题：$question")
            appendLine()
            append(
                """
                |回答要求：
                |1. 只依据上面列出的资料回答，不要使用资料以外的知识
                |2. 每个结论后面用 [编号] 标注来源，例如 [1]
                |3. 资料里没有的信息，直接回答「资料中没有提到」，不要推测
                |4. 用中文，简洁
                """.trimMargin()
            )
        }
    }

    /**
     * 仅检索模式：不调用模型，直接把最相关的原文段落给用户。
     *
     * 这是模型跑不动时的最后一层兜底。它存在的理由很实际：
     * 用户问"那个参数写在哪"，要的往往就是原文，而不是一段生成的解释。
     * BM25 是纯 CPU 字符串匹配，几 MB 内存，跟 LLM 完全无关——
     * 所以哪怕设备连模型都加载不了，这个功能依然成立。
     */
    fun buildExtractiveAnswer(question: String, hits: List<RagHit>): String {
        if (hits.isEmpty()) {
            return "没有找到与「$question」相关的内容。试试换个说法，或先到资料库补充文档。"
        }
        return buildString {
            appendLine("找到 ${hits.size} 段相关内容：")
            appendLine()
            hits.forEach { hit ->
                val heading = hit.chunk.headingPath.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                appendLine("[${hit.rank}] 《${hit.chunk.docName}》$heading")
                appendLine(hit.chunk.text.trim())
                appendLine()
            }
            append("—— 以上为原文摘录（仅检索模式，未调用模型）")
        }
    }

    /**
     * 从回答文本里解析引用编号，映射回真实来源。
     * 模型可能写 [1] 也可能写 [1][2]，还可能写一个不存在的编号——后者直接丢弃。
     */
    fun extractCitations(answer: String, hits: List<RagHit>): List<Citation> {
        val found = CITATION.findAll(answer).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        return found.sorted().mapNotNull { rank ->
            val hit = hits.firstOrNull { it.rank == rank } ?: return@mapNotNull null
            Citation(
                docId = hit.chunk.docId,
                docName = hit.chunk.docName,
                chunkIndex = hit.chunk.index,
                snippet = hit.chunk.text.take(60),
                score = hit.score
            )
        }
    }

    private companion object {
        val CITATION = Regex("""\[(\d{1,2})]""")
    }
}
