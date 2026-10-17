package com.liteagent.app.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用解析的健壮性测试。
 *
 * 用例全部来自"小模型真实会输出的样子"：加了寒暄、包了代码块、参数里带花括号。
 * 这不是过度设计——1.5B 模型十次里有三次不按格式来，解析器必须扛住。
 */
class ToolCallParserTest {

    @Test
    fun `纯 JSON 直接解析`() {
        val raw = """{"tool":"set_reminder","args":{"content":"开会","time":"2026-10-18 15:00"}}"""
        val call = ToolCallParser.parse(raw)
        assertEquals("set_reminder", call?.tool)
        assertEquals("开会", call?.args?.get("content"))
        assertEquals("2026-10-18 15:00", call?.args?.get("time"))
    }

    @Test
    fun `带代码块包裹`() {
        val raw = """
            好的，我来帮你设置。
            ```json
            {"tool":"set_reminder","args":{"content":"喝水","time":"2026-10-18 09:00"}}
            ```
            已经安排好了。
        """.trimIndent()

        val call = ToolCallParser.parse(raw)
        assertEquals("set_reminder", call?.tool)
        assertEquals("喝水", call?.args?.get("content"))
    }

    @Test
    fun `前面有寒暄后面有解释`() {
        val raw = "明白，这就办。{\"tool\":\"set_reminder\",\"args\":{\"content\":\"交报告\",\"time\":\"2026-10-19 10:30\"}} 设置完成了。"
        val call = ToolCallParser.parse(raw)
        assertEquals("交报告", call?.args?.get("content"))
    }

    @Test
    fun `参数值里含右花括号时不截断`() {
        val raw = """{"tool":"set_reminder","args":{"content":"讨论 {架构} 方案","time":"2026-10-18 14:00"}}"""
        val call = ToolCallParser.parse(raw)
        assertEquals("讨论 {架构} 方案", call?.args?.get("content"))
    }

    @Test
    fun `字符串里的转义引号不影响括号匹配`() {
        val raw = """{"tool":"set_reminder","args":{"content":"他说\"马上\"就来 }","time":"2026-10-18 14:00"}}"""
        val call = ToolCallParser.parse(raw)
        assertTrue(call?.args?.get("content")?.contains("马上") == true)
    }

    @Test
    fun `普通回复返回 null`() {
        assertNull(ToolCallParser.parse("今天天气不错，适合出门散步。"))
    }

    @Test
    fun `残缺 JSON 返回 null`() {
        assertNull(ToolCallParser.parse("""{"tool":"set_reminder","args":{"content":"开会""""))
    }

    @Test
    fun `缺少 tool 字段返回 null`() {
        assertNull(ToolCallParser.parse("""{"args":{"content":"开会"}}"""))
    }

    @Test
    fun `无参数工具能解析`() {
        val call = ToolCallParser.parse("""{"tool":"read_clipboard","args":{}}""")
        assertEquals("read_clipboard", call?.tool)
        assertTrue("args 应为空 map", call?.args?.isEmpty() == true)
    }

    @Test
    fun `省略 args 字段也能解析`() {
        // 小模型经常偷懒不写 args，@Serializable 的默认值要能兜住
        val call = ToolCallParser.parse("""{"tool":"list_today_events"}""")
        assertEquals("list_today_events", call?.tool)
        assertTrue(call?.args?.isEmpty() == true)
    }

    @Test
    fun `三个工具名都能被识别`() {
        val specs = listOf(ToolSpec.SET_REMINDER, ToolSpec.READ_CLIPBOARD, ToolSpec.LIST_TODAY_EVENTS)
        assertEquals(3, specs.toSet().size)
        specs.forEach { name ->
            assertTrue("system prompt 里应包含 $name", ToolSpec.prompt.contains(name))
        }
    }

    @Test
    fun `提取块从第一个左括号开始`() {
        val block = ToolCallParser.extractJsonBlock("说明文字 {\"a\":1} 尾注")
        assertEquals("""{"a":1}""", block)
    }
}
