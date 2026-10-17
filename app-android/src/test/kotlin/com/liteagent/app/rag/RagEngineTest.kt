package com.liteagent.app.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagEngineTest {

    private val chunks = listOf(
        Chunk("d1", "方案.md", 0, "技术选型", "推理框架选用 MNN，量化精度为 W4，模型体积约 500MB。"),
        Chunk("d1", "方案.md", 1, "性能目标", "首 token 延迟目标控制在 1.5 秒以内，decode 速度目标 10 tok/s。"),
        Chunk("d2", "笔记.md", 0, "", "今天下午去咖啡店见朋友，顺便取快递。")
    )

    private fun engine(): RagEngine = RagEngine(topK = 2).apply { rebuild(chunks) }

    @Test
    fun `检索命中相关片段`() = runBlocking {
        val hits = engine().retrieve("量化精度是多少")
        assertTrue("必须召回", hits.isNotEmpty())
        assertEquals("d1", hits.first().chunk.docId)
        assertTrue(hits.first().chunk.text.contains("W4"))
    }

    @Test
    fun `无关问题不召回`() = runBlocking {
        val hits = engine().retrieve("明天股市怎么走")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `topK 生效`() = runBlocking {
        val hits = engine().retrieve("模型 精度 延迟", )
        assertTrue(hits.size <= 2)
    }

    @Test
    fun `prompt 包含编号与来源`() {
        val e = engine()
        val hits = runBlocking { e.retrieve("量化精度") }
        val prompt = e.buildPrompt("量化精度是多少", hits)

        assertTrue("应带编号", prompt.contains("[1]"))
        assertTrue("应带文件名", prompt.contains("《方案.md》"))
        assertTrue("应带章节", prompt.contains("技术选型"))
        assertTrue("应带原问题", prompt.contains("问题：量化精度是多少"))
        assertTrue("应禁止编造", prompt.contains("资料中没有提到"))
    }

    @Test
    fun `无命中时退回原始问题`() {
        val prompt = engine().buildPrompt("明天天气怎么样", emptyList())
        assertEquals("明天天气怎么样", prompt)
    }

    @Test
    fun `解析引用编号`() {
        val e = engine()
        val hits = runBlocking { e.retrieve("量化精度") }
        val citations = e.extractCitations("量化精度是 W4 [1]，体积约 500MB [1]。", hits)

        assertEquals(1, citations.size)
        assertEquals("方案.md", citations.first().docName)
    }

    @Test
    fun `不存在的编号被丢弃`() {
        val e = engine()
        val hits = runBlocking { e.retrieve("量化精度") }
        val citations = e.extractCitations("这是答案 [9]", hits)
        assertTrue("越界编号不应映射出引用", citations.isEmpty())
    }

    @Test
    fun `重建索引会清掉旧文档`() {
        val e = engine()
        e.rebuild(listOf(Chunk("d3", "新.md", 0, "", "完全不同的内容，讲的是园艺修剪技巧。")))
        val hits = runBlocking { e.retrieve("量化精度") }
        assertTrue("旧文档必须被清掉，否则删了文档还能被检索到", hits.isEmpty())
    }
}
