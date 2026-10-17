package com.liteagent.app.ui

import com.liteagent.shared.ChatMessage
import com.liteagent.shared.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息组装逻辑的边界测试。
 *
 * 这里的每个用例都对应一个真机上踩过的坑，不是凭空设计的：
 *
 * - 「当前问题发两遍」：dropLast(1) 剥掉的是空的 assistant 占位，不是用户消息，
 *   结果模型收到两条一样的问句，回答变成"这个数字是什么？"重复 48 次。
 * - 「单条超长被丢弃」：0.5B 一轮回答 650 字，预算 300，`break` 让整条被丢掉，
 *   历史直接变空。用户问"刚才那个"，模型完全不知道指什么。
 * - 「尾部空占位」：流式输出用的容器消息内容为空，发给模型是噪音。
 */
class MessageAssemblerTest {

    private fun msg(id: String, role: ChatRole, content: String) =
        ChatMessage(id = id, role = role, content = content, timestamp = 0L)

    @Test
    fun `没有历史时只有 system 和当前问题`() {
        val out = MessageAssembler.assemble(
            system = "你是助理",
            messages = listOf(msg("u1", ChatRole.USER, "你好")),
            currentPrompt = "你好",
            lastUserId = "u1"
        )
        assertEquals(2, out.size)
        assertEquals("system", out[0].first)
        assertEquals("user", out[1].first)
        assertEquals("你好", out[1].second)
    }

    @Test
    fun `一轮历史会带上一问一答`() {
        val messages = listOf(
            msg("u1", ChatRole.USER, "什么是MNN"),
            msg("a1", ChatRole.ASSISTANT, "MNN是阿里开源的推理框架"),
            msg("u2", ChatRole.USER, "它和llama.cpp比呢")
        )
        val out = MessageAssembler.assemble(
            system = "你是助理",
            messages = messages,
            currentPrompt = "它和llama.cpp比呢",
            lastUserId = "u2"
        )
        assertEquals(4, out.size)
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.first })
        assertEquals("什么是MNN", out[1].second)
        assertEquals("MNN是阿里开源的推理框架", out[2].second)
    }

    /** 这是最贵的那个 bug：同一个问题发两遍，模型会开始复读 */
    @Test
    fun `当前问题不会出现两次`() {
        val question = "我刚才说的数字是多少"
        val messages = listOf(
            msg("u1", ChatRole.USER, "记住7391"),
            msg("a1", ChatRole.ASSISTANT, "好的，我记住了"),
            msg("u2", ChatRole.USER, question)
        )
        val out = MessageAssembler.assemble(
            system = "你是助理",
            messages = messages,
            currentPrompt = question,
            lastUserId = "u2"
        )
        val occurrences = out.count { it.second == question }
        assertEquals("当前问题只应出现一次", 1, occurrences)
    }

    @Test
    fun `尾部空的 assistant 占位会被过滤`() {
        val messages = listOf(
            msg("u1", ChatRole.USER, "你好"),
            msg("a1", ChatRole.ASSISTANT, ""),   // 流式输出的空容器
            msg("u2", ChatRole.USER, "在吗")
        )
        val out = MessageAssembler.assemble(
            system = "你是助理",
            messages = messages,
            currentPrompt = "在吗",
            lastUserId = "u2"
        )
        assertTrue("空内容不应出现在结果里", out.none { it.second.isBlank() })
    }

    /** 单条超预算时截断保留末尾，而不是整条丢掉 */
    @Test
    fun `单条超长消息会被截断而不是丢弃`() {
        val longAnswer = "开头" + "铺垫".repeat(200) + "结论在这里"
        val messages = listOf(
            msg("u1", ChatRole.USER, "解释一下"),
            msg("a1", ChatRole.ASSISTANT, longAnswer),
            msg("u2", ChatRole.USER, "再简要说一遍")
        )
        val out = MessageAssembler.assemble(
            system = "你是助理",
            messages = messages,
            currentPrompt = "再简要说一遍",
            charBudget = 300,
            lastUserId = "u2"
        )
        val assistantPart = out.firstOrNull { it.first == "assistant" }
        assertTrue("超长回答必须被保留（截断），不能整条丢掉", assistantPart != null)
        assertTrue("截断后长度不超过预算", (assistantPart?.second?.length ?: 0) <= 300)
        assertTrue("应保留末尾的结论", assistantPart?.second?.endsWith("结论在这里") == true)
    }

    @Test
    fun `预算不够时优先保留最近的消息`() {
        val messages = listOf(
            msg("u1", ChatRole.USER, "第一轮问题".repeat(20)),      // 100 字
            msg("a1", ChatRole.ASSISTANT, "第一轮回答".repeat(20)), // 100 字
            msg("u2", ChatRole.USER, "第二轮问题"),
            msg("a2", ChatRole.ASSISTANT, "第二轮回答"),
            msg("u3", ChatRole.USER, "第三轮问题")
        )
        val out = MessageAssembler.assemble(
            system = "你是助理",
            messages = messages,
            currentPrompt = "第三轮问题",
            charBudget = 30,
            lastUserId = "u3"
        )
        val history = out.drop(1).dropLast(1)   // 去掉 system 和当前问题

        // 最近的一轮必须完整保留
        assertTrue("第二轮应完整保留", history.any { it.second == "第二轮回答" })

        // 更早的内容只能以"截断片段"的形式存在，不能是完整原文。
        // 注意：不能断言"完全不含第一轮"——截断逻辑保留末尾，
        // 早前的内容仍可能以碎片形式占满剩余预算，这是设计意图。
        val firstRoundPieces = history.filter { it.second.contains("第一轮") }
        assertTrue(
            "第一轮不应以完整原文出现（100 字 > 30 预算）",
            firstRoundPieces.all { it.second.length < 100 }
        )

        // 总长度受预算约束
        val total = history.sumOf { it.second.length }
        assertTrue("历史总长不应超过预算", total <= 30)
    }

    @Test
    fun `摘要会插在 system 之后、历史之前`() {
        val messages = listOf(
            msg("u1", ChatRole.USER, "早期问题"),
            msg("a1", ChatRole.ASSISTANT, "早期回答"),
            msg("u2", ChatRole.USER, "当前问题")
        )
        val out = MessageAssembler.assemble(
            system = "你是助理",
            messages = messages,
            currentPrompt = "当前问题",
            summary = "用户问过节电模式",
            summarizedCount = 2,
            lastUserId = "u2"
        )
        assertEquals("system", out[0].first)
        assertEquals("assistant", out[1].first)
        assertTrue("摘要应紧跟 system", out[1].second.contains("节电模式"))
        assertFalse("已摘要的消息不再重发", out.any { it.second == "早期问题" })
    }

    @Test
    fun `空的历史消息不会占预算`() {
        val messages = listOf(
            msg("u1", ChatRole.USER, ""),
            msg("a1", ChatRole.ASSISTANT, "有效回答"),
            msg("u2", ChatRole.USER, "问题")
        )
        val out = MessageAssembler.assemble(
            system = "你是助理",
            messages = messages,
            currentPrompt = "问题",
            charBudget = 300,
            lastUserId = "u2"
        )
        assertTrue(out.none { it.second.isBlank() })
        assertTrue(out.any { it.second == "有效回答" })
    }
}
