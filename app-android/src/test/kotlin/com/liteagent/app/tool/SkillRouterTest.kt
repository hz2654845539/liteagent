package com.liteagent.app.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillRouterTest {

    @Test
    fun `命中简报技能`() {
        assertEquals("daily-briefing", SkillRouter.match("帮我出个今天的简报")?.id)
        assertEquals("daily-briefing", SkillRouter.match("今天要做什么")?.id)
    }

    @Test
    fun `命中会议纪要`() {
        assertEquals("meeting-notes", SkillRouter.match("把这段整理成纪要")?.id)
        assertEquals("meeting-notes", SkillRouter.match("会议纪要怎么写")?.id)
    }

    @Test
    fun `命中文件归档`() {
        assertEquals("file-organize", SkillRouter.match("下载文件夹太乱，帮我整理文件")?.id)
    }

    @Test
    fun `普通问题不命中任何技能`() {
        assertNull(SkillRouter.match("什么是端侧推理"))
        assertNull(SkillRouter.match("你好"))
    }

    @Test
    fun `三个技能文件都已注册`() {
        assertEquals(3, SkillRouter.skills.size)
        assertEquals(
            listOf("daily-briefing", "meeting-notes", "file-organize"),
            SkillRouter.skills.map { it.id }
        )
    }
}
