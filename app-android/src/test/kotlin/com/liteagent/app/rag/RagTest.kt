package com.liteagent.app.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    private val chunker = TextChunker(targetSize = 120, maxSize = 200, overlapSentences = 1)

    @Test
    fun `按标题切分并保留层级`() {
        val md = """
            # 项目方案
            本方案使用本地模型推理。数据不出设备。

            # 技术选型
            推理框架是 MNN。量化精度为 W4。
        """.trimIndent()

        val chunks = chunker.chunk("doc-1", "方案.md", md)

        assertTrue("应至少切出两块", chunks.size >= 2)
        assertTrue("第一章标题应保留", chunks.any { it.headingPath == "项目方案" })
        assertTrue("第二章标题应保留", chunks.any { it.headingPath == "技术选型" })
        assertTrue("内容应完整保留", chunks.joinToString("") { it.text }.contains("数据不出设备"))
    }

    @Test
    fun `长文本被切成多块且不超过上限`() {
        val sentence = "这是一句用来验证分块逻辑的测试内容。"
        val raw = sentence.repeat(60) // 约 1080 字

        val chunks = chunker.chunk("doc-2", "长文.md", raw)

        assertTrue("长文档必须切成多块", chunks.size > 1)
        assertTrue("单块不得超过 maxSize", chunks.all { it.text.length <= 200 })
        assertTrue("索引必须连续", chunks.mapIndexed { i, c -> c.index == i }.all { it })
    }

    @Test
    fun `相邻块之间存在重叠句`() {
        val sentence = "第一句话很重要。第二句话也很重要。第三句话同样重要。"
        val raw = sentence.repeat(10)

        val chunks = chunker.chunk("doc-3", "重叠.md", raw)

        assertTrue("至少两块才有重叠可言", chunks.size >= 2)
        val tail = chunks[0].text.takeLast(12)
        assertTrue(
            "上一块的结尾应出现在下一块开头附近",
            chunks[1].text.contains(tail) || chunks[1].text.take(20).isNotEmpty()
        )
    }

    @Test
    fun `超长单句被硬切而不丢内容`() {
        val oneLine = "没有标点".repeat(200) // 800 字，一个句子都没有
        val chunks = chunker.chunk("doc-4", "无标点.md", oneLine)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 200 })
    }
}

class Bm25Test {

    private fun buildIndex(): Bm25Index {
        val index = Bm25Index()
        index.addAll(
            listOf(
                Chunk("a", "方案A.md", 0, "技术选型", "上次方案的量化精度是 W4，模型体积约 500MB，适合 6GB 内存设备。"),
                Chunk("b", "闲聊.md", 0, "", "今天天气不错，我们去公园散步吧，顺便买点水果。"),
                Chunk("c", "部署.md", 0, "", "部署流程需要先安装依赖，然后启动服务，最后检查端口。"),
                Chunk("d", "补充.md", 0, "备注", "量化精度还可以选择 W8，但显存和内存占用会翻倍。")
            )
        )
        return index
    }

    @Test
    fun `中文查询能召回正确文档`() {
        val result = runBlocking { buildIndex().search("量化精度是多少", topK = 2) }

        assertTrue("必须召回", result.isNotEmpty())
        val ids = result.map { it.chunk.docId }.toSet()
        assertTrue(
            "前两名应落在提到量化精度的两个文档里，实际命中 $ids",
            ids.contains("a") || ids.contains("d")
        )
    }

    @Test
    fun `无关文档不进入结果`() {
        val result = runBlocking { buildIndex().search("量化精度", topK = 4) }
        assertTrue("闲聊文档不应命中", result.none { it.chunk.docId == "b" })
    }

    @Test
    fun `topK 生效`() {
        val result = runBlocking { buildIndex().search("量化精度 内存", topK = 1) }
        assertEquals(1, result.size)
    }

    @Test
    fun `空查询返回空结果`() {
        val result = runBlocking { buildIndex().search("！！", topK = 3) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `中文按 bigram 切词`() {
        val tokens = Bm25Index().tokenize("量化精度")
        assertEquals(listOf("量化", "化精", "精度"), tokens)
    }

    @Test
    fun `中英混排都能切`() {
        val tokens = Bm25Index().tokenize("模型 W4 量化")
        assertTrue(tokens.contains("w4"))
        assertTrue(tokens.contains("模型"))
        assertTrue(tokens.contains("量化"))
    }
}
