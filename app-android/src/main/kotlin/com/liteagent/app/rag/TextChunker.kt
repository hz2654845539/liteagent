package com.liteagent.app.rag

/** 一个可被检索的文本块。headingPath 保留文档层级，回答时能告诉用户"引自第 2 章第 3 段"。 */
data class Chunk(
    val docId: String,
    val docName: String,
    val index: Int,
    val headingPath: String,
    val text: String
)

/**
 * 文档分块。
 *
 * 三个刻意的设计：
 * 1. **按标题切分**：Markdown 标题是天然语义边界，比按字数硬切好得多；
 * 2. **按句子累积**：不会把一个句子劈成两半，检索出来的片段是可读的；
 * 3. **重叠一句**：避免答案正好落在两个块的交界处时两边都召不全。
 */
class TextChunker(
    private val targetSize: Int = 400,
    private val maxSize: Int = 600,
    private val overlapSentences: Int = 1
) {

    fun chunk(docId: String, docName: String, raw: String): List<Chunk> {
        val result = ArrayList<Chunk>()
        var heading = ""
        val section = StringBuilder()

        fun flush() {
            if (section.isBlank()) return
            appendSection(result, docId, docName, heading, section.toString())
            section.setLength(0)
        }

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                flush()
                heading = trimmed.trimStart('#').trim()
            } else {
                if (section.isNotEmpty()) section.append('\n')
                section.append(line)
            }
        }
        flush()
        return result.mapIndexed { i, c -> c.copy(index = i) }
    }

    private fun appendSection(
        out: MutableList<Chunk>,
        docId: String,
        docName: String,
        heading: String,
        body: String
    ) {
        val sentences = splitSentences(body)
        if (sentences.isEmpty()) return

        var current = ArrayList<String>()
        var currentLen = 0

        fun emit() {
            if (current.isEmpty()) return
            out += Chunk(docId, docName, 0, heading, current.joinToString("").trim())
            val candidate = current.takeLast(overlapSentences).filter { it.isNotBlank() }
            val keepLen = candidate.sumOf { it.length }
            // 重叠句太长时不再保留：否则下一句进来立刻又触发 emit，
            // 同一句会同时出现在前一块的结尾和后一块的开头，检索时形成近似重复条目
            if (keepLen * 2 <= targetSize) {
                current = ArrayList(candidate)
                currentLen = keepLen
            } else {
                current = ArrayList()
                currentLen = 0
            }
        }

        for (sentence in sentences) {
            if (sentence.isBlank()) continue
            // 单句超过上限：硬切，避免一个超长段落拖垮整块
            if (sentence.length > maxSize) {
                emit()
                var start = 0
                while (start < sentence.length) {
                    val end = minOf(start + targetSize, sentence.length)
                    out += Chunk(docId, docName, 0, heading, sentence.substring(start, end))
                    start = end
                }
                current = ArrayList()
                currentLen = 0
                continue
            }
            if (currentLen + sentence.length > targetSize && current.isNotEmpty()) emit()
            current += sentence
            currentLen += sentence.length
        }
        if (current.isNotEmpty()) {
            out += Chunk(docId, docName, 0, heading, current.joinToString("").trim())
        }
    }

    /** 按中英文句末标点切句，标点跟着前一句走 */
    private fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (ch in text) {
            buffer.append(ch)
            if (ch in SENTENCE_ENDS) {
                out += buffer.toString()
                buffer.setLength(0)
            }
        }
        if (buffer.isNotBlank()) out += buffer.toString()
        return out
    }

    private companion object {
        val SENTENCE_ENDS = charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n')
    }
}
