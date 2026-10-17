package com.liteagent.app.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RawToolCall(
    val tool: String,
    val args: Map<String, String> = emptyMap()
)

/**
 * 工具调用解析。
 *
 * 为什么不用模型原生 function calling：1.5B 级别的模型在这个任务上极不稳定，
 * 不同模型还要各自的模板。改成"让模型输出固定 JSON，客户端解析"，
 * 行为可控、失败可重试、换模型不用改代码。
 *
 * 难点不在 JSON 本身，而在**模型从来不会老老实实只输出 JSON**：
 * 它会加"好的，我来帮你设置："，会用 ```json 包起来，会在结尾补一句解释。
 * 所以这里做的第一件事是从自由文本里把 JSON 块抠出来。
 */
object ToolCallParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 返回 null 表示这条输出不是工具调用（应当按普通回复处理） */
    fun parse(raw: String): RawToolCall? {
        val block = extractJsonBlock(raw) ?: return null
        val call = runCatching { json.decodeFromString<RawToolCall>(block) }.getOrNull() ?: return null
        if (call.tool.isBlank()) return null
        return call
    }

    /**
     * 从自由文本里提取第一个括号配平的 JSON 对象。
     *
     * 必须自己扫括号而不是用 indexOf('}')：参数值里可能带 "}"，
     * 也可能出现嵌套对象。上面的状态机同时处理了字符串转义。
     */
    internal fun extractJsonBlock(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }
}

/**
 * 工具的 schema 描述，直接拼进 system prompt。
 * 注意：字段说明写得越具体，小模型出错的概率越低——这不是"提示词技巧"，是接口契约。
 */
object ToolSpec {

    const val SET_REMINDER = "set_reminder"
    const val READ_CLIPBOARD = "read_clipboard"
    const val LIST_TODAY_EVENTS = "list_today_events"

    val prompt: String = """
你可以调用工具。需要调用时，**只输出一个 JSON 对象**，不要输出任何其他文字、不要加解释、不要用代码块包裹。

可用工具：
1. set_reminder —— 设置提醒
   {"tool":"set_reminder","args":{"content":"提醒内容","time":"yyyy-MM-dd HH:mm"}}
2. read_clipboard —— 读取用户剪贴板里的文字（无参数）
   {"tool":"read_clipboard","args":{}}
3. list_today_events —— 读取用户今天的日历日程（无参数）
   {"tool":"list_today_events","args":{}}

规则：
- time 必须是 24 小时制的 "yyyy-MM-dd HH:mm"，不要用"明天下午三点"这种说法
- 用户要看剪贴板、要总结复制的内容时，调用 read_clipboard
- 用户问今天有什么安排、要做简报时，调用 list_today_events
- 如果用户没有说清楚具体时间，不要调用 set_reminder，直接反问
- 不需要调用工具时，正常用中文回答
""".trimIndent()
}
