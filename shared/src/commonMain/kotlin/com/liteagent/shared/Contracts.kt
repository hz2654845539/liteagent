package com.liteagent.shared

import kotlinx.serialization.Serializable

/**
 * 跨端契约：Android 客户端与 FastAPI 后端共用。
 *
 * 设计原则：
 * 1. 只放「两端都需要的结构」，不放业务逻辑；
 * 2. 所有字段可序列化为 JSON，后端 Pydantic 模型按同一名字生成；
 * 3. 可选字段一律给默认值，保证向后兼容（后端加字段不会让旧客户端崩）。
 */

@Serializable
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/** RAG 回答的来源标注。点击可跳转原文，是"本地知识库"区别于普通聊天的核心证据。 */
@Serializable
data class Citation(
    val docId: String,
    val docName: String,
    val chunkIndex: Int,
    val snippet: String,
    val score: Float = 0f
)

@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val timestamp: Long,
    val citations: List<Citation> = emptyList(),
    /** 工具调用失败或模型输出不合规时的提示，UI 用它渲染灰色提示条 */
    val error: String? = null
)

/** 模型清单条目。后端 /models 与客户端本地清单结构一致。 */
@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val quantization: String,          // "W4" / "W8"
    val sizeBytes: Long,
    val sha256: String,
    val minMemoryMb: Long,
    val contextLength: Int,
    val downloadUrl: String,
    val vendor: String = "Qwen"
)

/**
 * 设备画像。
 *
 * 注意：这里刻意记录 [failures]，因为「探测失败」本身就是需要被观测的信息——
 * 优雅降级不等于静默失败，否则线上遇到某机型拿不到 SoC 型号时无从排查。
 */
@Serializable
data class DeviceProfile(
    /** Build.SOC_MODEL（API 31+），低版本回退 Build.HARDWARE */
    val socModel: String? = null,
    val manufacturer: String = "",
    val brand: String = "",
    val board: String = "",
    val hardware: String = "",
    val cpuCores: Int = 0,
    /** /sys/.../cpuinfo_max_freq 的最大值，单位 MHz；读不到为 null */
    val cpuMaxFreqMhz: Int? = null,
    /** 总内存（MB）。注意它比标称容量小 0.4-0.8GB，分级时必须先归并到标称档位 */
    val totalMemMb: Long = 0,
    val availMemMb: Long = 0,
    val memoryClassMb: Int = 0,
    val maxHeapMb: Long = 0,
    val freeStorageMb: Long = 0,
    val sdkInt: Int = 0,
    val supportedAbis: List<String> = emptyList(),
    val glesVersion: String = "",
    /**
     * GL_RENDERER 字符串，例如 "Adreno (TM) 740" / "Mali-G715"。
     * MNN 的 GPU backend 是 OpenCL，而 Android 没有公开的 OpenCL 查询 API，
     * 因此用 GPU 型号字符串 + GLES 版本做能力推断，比查 Vulkan feature flag 更贴近真实可用性。
     */
    val gpuRenderer: String? = null,
    val isArm64: Boolean = false,
    val collectedAt: Long = 0,
    /** 采集失败的字段名列表，用于排查兜底值是否被触发 */
    val failures: List<String> = emptyList()
)

/** 推荐结果。规则表外置成 JSON，客户端内置一份做回落，后端可下发覆盖。 */
@Serializable
data class ModelRecommendation(
    val modelId: String,
    val displayName: String,
    val quantization: String,
    val contextLength: Int,
    val threads: Int,
    val useGpu: Boolean,
    /** mmap 加载权重：峰值内存显著下降，代价是首次加载变慢 */
    val useMmap: Boolean,
    /** 给用户看的一句话解释，设置页直接展示 */
    val reason: String,
    /** 规则命中来源：LOCAL_RULE / REMOTE_OVERRIDE */
    val source: String = "LOCAL_RULE"
)

/**
 * 性能埋点。五项基础指标直接来自 MNN 的 LlmContext，
 * [sustainedTps] 是额外的一列——连续生成后的稳定吞吐，反映降频幅度。
 */
@Serializable
data class PerfReport(
    val deviceFingerprint: String,
    val modelId: String,
    val quantization: String,
    val threads: Int,
    val useGpu: Boolean,
    val loadMs: Long,
    val prefillMs: Long,
    val ttftMs: Long,
    val decodeTps: Float,
    val peakMemMb: Long,
    /** 连续生成 512 token 时，后 100 token 的吞吐；用于量化降频 */
    val sustainedTps: Float? = null,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val timestamp: Long = 0
)

/** 模型请求调用的工具。参数用 string map 承接，由客户端做类型校验。 */
@Serializable
data class ToolCall(
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)

@Serializable
data class ToolResult(
    val name: String,
    val success: Boolean,
    val message: String,
    /** 回灌给模型的文本，不是给用户看的 */
    val modelFeedback: String = ""
)
