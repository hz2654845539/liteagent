package com.liteagent.app.tool

import android.content.Context

/**
 * 极简 Skills 加载器。
 *
 * 不做引擎、不做市场：就是把一段 Markdown 拼进 system prompt。
 * 这样加一个技能只需要丢一个 .md 文件，不用改代码、不用发版逻辑。
 * 触发靠关键词，够用且可预测——用模型判断"该用哪个技能"在 1.5B 上不可靠。
 */
object SkillRouter {

    data class Skill(
        val id: String,
        val file: String,
        val triggers: List<String>
    )

    val skills: List<Skill> = listOf(
        Skill(
            id = "daily-briefing",
            file = "daily-briefing.md",
            triggers = listOf("简报", "今天要做什么", "今天的安排", "今日安排", "今天干什么")
        ),
        Skill(
            id = "meeting-notes",
            file = "meeting-notes.md",
            triggers = listOf("会议纪要", "整理会议", "总结这个会", "会议记录", "整理成纪要")
        ),
        Skill(
            id = "file-organize",
            file = "file-organize.md",
            triggers = listOf("整理文件", "文件怎么分类", "归档", "文件夹太乱")
        )
    )

    fun match(input: String): Skill? =
        skills.firstOrNull { skill -> skill.triggers.any { input.contains(it) } }

    /** 读不到就返回 null，调用方按"没有技能"处理，不让它成为阻断路径 */
    fun load(context: Context, skill: Skill): String? = runCatching {
        context.assets.open("skills/${skill.file}").bufferedReader().use { it.readText() }
    }.getOrNull()
}
