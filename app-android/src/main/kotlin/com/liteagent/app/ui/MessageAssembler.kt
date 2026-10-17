package com.liteagent.app.ui

import com.liteagent.shared.ChatMessage
import com.liteagent.shared.ChatRole

/**
 * 组装「这次要发给模型的消息列表」。
 *
 * ───────────────────────────────────────────────────────────────
 * 【为什么单独抽出来】
 *
 * 这段逻辑原本写在 ChatViewModel 里，但它其实和界面、引擎都没关系——
 * 输入一堆消息，输出一个列表，是纯函数。纯函数就该能单独测，
 * 而放在 ViewModel 里就得先造 Android 环境才能跑测试，等于测不了。
 *
 * 这个文件的存在就是为了让 [assemble] 可以被单元测试覆盖。
 */
internal object MessageAssembler {

    /** 默认历史预算（字符）。真机实测：300 字历史 ≈ 9 秒首 token */
    const val DEFAULT_HISTORY_BUDGET = 300

    /**
     * 组装消息列表。
     *
     * 输出顺序（从上到下就是模型看到的顺序）：
     * ```
     * system    人格设定
     * assistant 之前对话的摘要（如果有）
     * user      较早的历史
     * assistant ...
     * user      当前问题
     * ```
     *
     * @param system          人格设定 + 工具说明
     * @param messages        完整的消息列表（含当前问题、含尾部空占位）
     * @param currentPrompt   当前问题。若与 messages 里最后一条用户消息重复，只保留这一个
     * @param summary         早期对话的摘要，空串表示还没压缩过
     * @param summarizedCount 前 N 条已被摘要覆盖，不再重发
     * @param charBudget      历史部分最多带多少字
     * @param lastUserId      当前问题的消息 id。传了就精确排除它，不传则按"最后一条用户消息"排除
     */
    fun assemble(
        system: String,
        messages: List<ChatMessage>,
        currentPrompt: String,
        summary: String = "",
        summarizedCount: Int = 0,
        charBudget: Int = DEFAULT_HISTORY_BUDGET,
        lastUserId: String? = null
    ): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()

        if (system.isNotBlank()) result.add("system" to system)

        // 摘要不是一条真实消息，但它占据了"早期对话"的位置，所以在历史之前
        if (summary.isNotBlank()) {
            result.add("assistant" to "之前对话的摘要：$summary")
        }

        val all = if (summarizedCount in 1 until messages.size) {
            messages.drop(summarizedCount)
        } else if (summarizedCount >= messages.size) {
            emptyList()
        } else {
            messages
        }

        // 清理一：尾部内容为空的消息（流式输出的占位容器，还没填内容）
        val trimmed = all.dropLastWhile { it.content.isBlank() }

        // 清理二：当前问题本身。它会作为最后一条单独追加，
        // 这里不排除就会发两遍——模型看到两条一样的问句会开始胡言乱语。
        val excludeIdx = if (lastUserId != null) {
            trimmed.indexOfFirst { it.id == lastUserId }
        } else {
            trimmed.indexOfLast { it.role == ChatRole.USER }
        }
        val history = trimmed.filterIndexed { idx, msg ->
            idx != excludeIdx && msg.content.isNotBlank()
        }

        // 从最近往前取，直到预算用完。越近的内容越相关
        val picked = ArrayDeque<Pair<String, String>>()
        var used = 0
        for (msg in history.asReversed()) {
            val remaining = charBudget - used
            if (remaining <= 0) break

            // 单条就超预算时截断而不是丢弃。
            // 踩过的坑：0.5B 一轮回答常有 600+ 字，整条丢掉会导致历史全空，
            // 用户问"刚才那个"，模型完全不知道指什么。
            // 保留末尾——回答的结论通常在最后，开头多是铺垫。
            val text = if (msg.content.length > remaining) {
                msg.content.takeLast(remaining)
            } else {
                msg.content
            }
            picked.addFirst(msg.role.name.lowercase() to text)
            used += text.length
        }
        result.addAll(picked)

        result.add("user" to currentPrompt)
        return result
    }
}
