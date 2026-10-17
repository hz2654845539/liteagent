package com.liteagent.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仅检索模式的输出格式测试。
 *
 * 这条路径不调用模型——纯 CPU 检索 + 字符串拼接，
 * 所以它是"设备连模型都加载不了时"的最后一层兜底（`docs/09` 里说的那句话）。
 *
 * 真机上这台设备的输入法拿不到焦点，`adb input text` 打不进中文，
 * 所以这条路只能靠单测保证正确性。这是它值得单独测的原因。
 */
class ExtractiveAnswerTest {

    private fun hit(rank: Int, doc: String, heading: String, text: String) = RagHit(
        rank = rank,
        chunk = Chunk(
            docId = "d1",
            docName = doc,
            index = rank,
            headingPath = heading,
            text = text
        ),
        score = 1.0f
    )

    @Test
    fun `没有命中时给出可操作的提示`() {
        val out = RagEngine().buildExtractiveAnswer("量化精度是多少", emptyList())
        assertTrue("应说明没找到", out.contains("没有找到"))
        assertTrue("应把问题原样带回，让用户知道查的是什么", out.contains("量化精度是多少"))
        assertTrue("应给出下一步建议", out.contains("换个说法") || out.contains("补充文档"))
    }

    @Test
    fun `命中时列出原文并标注来源`() {
        val hits = listOf(
            hit(1, "技术方案.md", "模型规格", "默认模型是 Qwen2.5-0.5B，量化精度 W4，体积 531MB")
        )
        val out = RagEngine().buildExtractiveAnswer("量化精度", hits)

        assertTrue("应说明找到几段", out.contains("找到 1 段"))
        assertTrue("应有编号", out.contains("[1]"))
        assertTrue("应有文档名", out.contains("技术方案.md"))
        assertTrue("应有标题路径", out.contains("模型规格"))
        assertTrue("应包含原文", out.contains("量化精度 W4"))
        assertTrue(
            "必须声明没调用模型——用户看到的是原文摘录，不是 AI 生成的",
            out.contains("未调用模型")
        )
    }

    @Test
    fun `多段命中时编号连续且都带来源`() {
        val hits = listOf(
            hit(1, "a.md", "部署", "第一段内容"),
            hit(2, "b.md", "性能", "第二段内容"),
            hit(3, "c.md", "", "第三段内容")
        )
        val out = RagEngine().buildExtractiveAnswer("内容", hits)

        assertTrue(out.contains("[1]"))
        assertTrue(out.contains("[2]"))
        assertTrue(out.contains("[3]"))
        assertTrue("三段的原文都应出现", out.contains("第一段内容") && out.contains("第三段内容"))
        assertTrue("没有标题路径的段落不应出现空的 · 分隔符", !out.contains("[3] 《c.md》 ·"))
    }
}
