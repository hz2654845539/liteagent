package com.liteagent.app.data

import kotlinx.serialization.Serializable

/**
 * 人格预设。
 *
 * 借鉴 PocketPal 的 Pals：不同场景用不同的 system prompt，比让用户自己写提示词现实得多。
 * 小模型的指令跟随能力有限，预设写得越具体，效果差异越明显——"翻译"预设只输出译文，
 * 比在通用助理后面加一句"请翻译"靠谱。
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val emoji: String,
    val systemPrompt: String
)

object PresetStore {

    val builtin: List<Preset> = listOf(
        Preset(
            id = "assistant",
            name = "助理",
            emoji = "🤖",
            systemPrompt = "你是一个运行在手机本地的 AI 助理。回答简洁准确，用中文。" +
                "不确定的事情直接说不确定，不要编造。"
        ),
        Preset(
            id = "translator",
            name = "翻译",
            emoji = "🌐",
            systemPrompt = "你是翻译。只输出译文，不要解释、不要加引号、不要说明你在翻译。" +
                "中译英保持简洁自然，英译中保持通顺口语化。"
        ),
        Preset(
            id = "coder",
            name = "代码",
            emoji = "⌨",
            systemPrompt = "你是资深 Android 工程师。优先给可直接运行的代码，少讲概念。" +
                "关键决策要说明为什么这样写，不要罗列多种方案让用户自己选。"
        ),
        Preset(
            id = "summarizer",
            name = "总结",
            emoji = "📋",
            systemPrompt = "你是总结器。把输入压缩成不超过三条要点，每条一句话。" +
                "不加评价，不写「综上所述」这类收尾句。"
        )
    )

    /** 找不到时回落到第一个，不让预设 ID 拼错变成崩溃点 */
    fun find(id: String?): Preset = builtin.firstOrNull { it.id == id } ?: builtin.first()
}
