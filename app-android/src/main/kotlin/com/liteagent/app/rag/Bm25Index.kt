package com.liteagent.app.rag

import kotlin.math.ln

data class ScoredChunk(val chunk: Chunk, val score: Float)

/**
 * 检索器接口。BM25 与向量两种实现共用这一个接口，
 * 后续加 embedding 检索时不需要改动上层。
 */
interface Retriever {
    suspend fun search(query: String, topK: Int = 3): List<ScoredChunk>
}

/**
 * BM25 检索（零依赖版本）。
 *
 * 中文为什么用字级 bigram 而不是分词：
 * jieba 类分词库要带 5MB 词典，而端侧 APK 体积是硬成本；
 * 字级 bigram（"量化精度" → 量化/化精/精度）在中文检索上召回接近分词，
 * 还顺手绕掉了 OOV（新词、型号名、专有名词）问题。
 */
class Bm25Index(
    private val k1: Double = 1.5,
    private val b: Double = 0.75
) : Retriever {

    private val chunks = ArrayList<Chunk>()
    private val termFreq = ArrayList<MutableMap<String, Int>>()
    private val docLength = ArrayList<Int>()
    private val docFreq = HashMap<String, Int>()
    private var avgLength = 0.0

    /** add() 之后统计量过期。search 时自动重建，避免调用方忘了调就静默得到全 0 分 */
    private var dirty = false

    fun addAll(items: List<Chunk>) {
        items.forEach { add(it) }
        rebuildStats()
    }

    fun add(chunk: Chunk) {
        val tokens = tokenize(chunk.text)
        val tf = HashMap<String, Int>()
        tokens.forEach { tf[it] = (tf[it] ?: 0) + 1 }
        chunks += chunk
        termFreq += tf
        docLength += tokens.size
        dirty = true
    }

    fun rebuildStats() {
        docFreq.clear()
        termFreq.forEach { tf ->
            tf.keys.forEach { docFreq[it] = (docFreq[it] ?: 0) + 1 }
        }
        avgLength = if (docLength.isEmpty()) 0.0 else docLength.average()
        dirty = false
    }

    /** 重建索引前清空，避免文档被删后旧块还在参与打分 */
    fun clear() {
        chunks.clear()
        termFreq.clear()
        docLength.clear()
        docFreq.clear()
        avgLength = 0.0
        dirty = false
    }

    override suspend fun search(query: String, topK: Int): List<ScoredChunk> {
        if (dirty) rebuildStats()
        val qTokens = tokenize(query).distinct()
        if (qTokens.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()

        return chunks.indices
            .map { i ->
                val tf = termFreq[i]
                val dl = docLength[i]
                var score = 0.0
                for (term in qTokens) {
                    val f = tf[term] ?: continue
                    val df = docFreq[term] ?: continue
                    val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
                    val denom = f + k1 * (1 - b + b * dl / (if (avgLength > 0) avgLength else 1.0))
                    score += idf * (f * (k1 + 1)) / denom
                }
                ScoredChunk(chunks[i], score.toFloat())
            }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /** 混排文本切词：中文连续段切成 bigram，英文数字按词并转小写。 */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        for (match in SEGMENT.findAll(text)) {
            val seg = match.value
            if (seg[0] in CJK_RANGE) {
                if (seg.length == 1) {
                    out += seg
                } else {
                    for (i in 0 until seg.length - 1) out += seg.substring(i, i + 2)
                }
            } else {
                val word = seg.lowercase()
                if (word.length > 1) out += word
            }
        }
        return out
    }

    private companion object {
        val SEGMENT = Regex("[\\u4e00-\\u9fa5]+|[A-Za-z0-9]+")
        val CJK_RANGE = '\u4e00'..'\u9fa5'
    }
}
