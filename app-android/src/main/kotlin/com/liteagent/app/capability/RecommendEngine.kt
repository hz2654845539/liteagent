package com.liteagent.app.capability

import com.liteagent.shared.DeviceProfile
import com.liteagent.shared.ModelRecommendation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * 规则数据结构。与 `rules/recommend-rules.json` 一一对应。
 * 后端读的是同一个文件，所以这里不能另写一套默认值，否则必然漂移。
 */
@Serializable
data class RecommendRules(
    val baseline: BaselineRule? = null,
    val unsupported: UnsupportedRule,
    /** 内存紧张但还能跑的档位。没有这一段就等于不支持极限模式 */
    val extreme: ExtremeRule? = null,
    val memTiers: List<MemTier>,
    val threads: ThreadRule,
    val gpu: GpuRule,
    val mmap: MmapRule,
    val sampling: SamplingRule? = null
)

/**
 * 极限档：1500–3500MB。
 *
 * 这个档位存在的理由不是"照顾低端机"，而是实测发现 0.5B 的词嵌入走磁盘
 * （MNN 的 DiskEmbedding），权重之外的大头不占内存，所以 2GB 设备有机会跑起来。
 * 但必须 mmap + 短上下文 + 少线程，且要如实告诉用户会慢。
 */
@Serializable
data class ExtremeRule(
    val aboveTotalMemMb: Long,
    val belowTotalMemMb: Long,
    val modelId: String,
    val contextLength: Int,
    val threads: Int,
    val useGpu: Boolean = false,
    val reason: String
)

@Serializable
data class BaselineRule(
    val nominalGb: Int = 8,
    val socClass: String = "",
    val androidVersion: Int = 14
)

@Serializable
data class UnsupportedRule(
    val belowTotalMemMb: Long,
    val message: String
)

@Serializable
data class MemTier(
    val nominalGb: Int,
    val modelId: String,
    val quantization: String,
    val contextLength: Int,
    val reason: String
)

@Serializable
data class ThreadRule(
    val defaultCores: Int = 4,
    val maxCores: Int = 6
)

@Serializable
data class GpuRule(
    val minGlesMajor: Int = 3,
    val supportedRendererPrefixes: List<String> = emptyList()
)

@Serializable
data class MmapRule(
    val enabledWhenFreeStorageMbAbove: Long = 4096,
    val enabledWhenTotalMemMbBelow: Long = 8192
)

@Serializable
data class SamplingRule(val temperature: Double = 0.7, val topP: Double = 0.9)

/** 推荐结果：给一套配置、给一套压到最低的配置、或者明确说"跑不了"。 */
sealed interface Recommendation {
    data class Supported(val model: ModelRecommendation) : Recommendation

    /**
     * 极限档。参数已经压到底，用户需要知道"能用但慢"，
     * 所以 UI 要单独呈现，不能和正常推荐长一样。
     */
    data class Restricted(val model: ModelRecommendation) : Recommendation

    data class Unsupported(val message: String) : Recommendation
}

/** 支持与极限档都能拿到模型配置，UI 和引擎侧统一用这个取。 */
fun Recommendation?.modelOrNull(): ModelRecommendation? = when (this) {
    is Recommendation.Supported -> model
    is Recommendation.Restricted -> model
    else -> null
}

/**
 * 设备 → 模型/参数推荐。
 *
 * 这里刻意做成**纯函数 + 构造注入规则**，不碰 Context，理由是：
 * 1. 规则边界最容易错，必须能用单测钉住；
 * 2. 后端要复用同一套逻辑，逻辑不能绑在 Android 上。
 */
class RecommendEngine(private val rules: RecommendRules) {

    fun recommend(profile: DeviceProfile): Recommendation {
        // 规则表被改坏时给明确结论，而不是在 UI 初始化路径上抛 NPE
        if (rules.memTiers.isEmpty()) {
            return Recommendation.Unsupported("规则表为空，无法给出推荐")
        }

        // 第一条：装不下就别装。硬推一个小模型然后崩，比明确拒绝更伤用户。
        if (profile.totalMemMb in 1 until rules.unsupported.belowTotalMemMb) {
            return Recommendation.Unsupported(
                rules.unsupported.message.replace("X", formatGb(profile.totalMemMb))
            )
        }

        // 第二条：内存紧张但够用的区间，给极限配置，并明确标注"会慢"
        val extreme = rules.extreme
        if (extreme != null &&
            profile.totalMemMb in extreme.aboveTotalMemMb until extreme.belowTotalMemMb
        ) {
            return Recommendation.Restricted(
                ModelRecommendation(
                    modelId = extreme.modelId,
                    displayName = extreme.modelId,
                    quantization = "W4",
                    contextLength = extreme.contextLength,
                    threads = extreme.threads.coerceAtMost(profile.cpuCores.coerceAtLeast(1)),
                    // 极限档强制关 GPU：统一内存架构下 GPU 会和 CPU 抢同一块 RAM
                    useGpu = false,
                    // 强制 mmap：不 mmap 的话权重整块进 RSS，2GB 设备必然被 LMK 杀
                    useMmap = true,
                    reason = "${formatGb(profile.totalMemMb)} 内存 · ${extreme.reason}",
                    source = "LOCAL_RULE_EXTREME"
                )
            )
        }

        val tier = pickTier(profile.totalMemMb)
        val threads = pickThreads(profile.cpuCores)
        val useGpu = canUseGpu(profile)
        val useMmap = shouldUseMmap(profile)

        val reasonParts = buildList {
            add("${formatGb(profile.totalMemMb)} 内存")
            profile.socModel?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (useGpu) add("开启 GPU(OpenCL)")
            if (useMmap) add("mmap 加载")
            add(tier.reason)
        }

        return Recommendation.Supported(
            ModelRecommendation(
                modelId = tier.modelId,
                displayName = tier.modelId,
                quantization = tier.quantization,
                contextLength = tier.contextLength,
                threads = threads,
                useGpu = useGpu,
                useMmap = useMmap,
                reason = reasonParts.joinToString(" · "),
                source = "LOCAL_RULE"
            )
        )
    }

    /**
     * 把实测总内存归并到标称档位。
     *
     * 为什么不能直接比阈值：`totalMem` 永远比标称小 0.4–0.8GB（内核 + 保留内存）。
     * 8GB 机实测约 7.5GB，直接按 "8-12GB" 判会落到 6-8 档；
     * 6GB 机实测约 5.6GB，会掉进 "< 6GB" 去用 0.5B。
     * 先补偿再就近归并，才能得到用户心里的"我这台是 8GB 手机"。
     */
    fun nominalGb(totalMemMb: Long): Int {
        val adjusted = totalMemMb / 1024.0 + RESERVED_MEM_GB
        return rules.memTiers
            .minByOrNull { abs(it.nominalGb - adjusted) }
            ?.nominalGb
            ?: DEFAULT_NOMINAL_GB
    }

    private fun pickTier(totalMemMb: Long): MemTier {
        val target = nominalGb(totalMemMb)
        return rules.memTiers.firstOrNull { it.nominalGb == target }
            ?: rules.memTiers.minByOrNull { it.nominalGb }!!
    }

    private fun pickThreads(cpuCores: Int): Int {
        if (cpuCores <= 0) return rules.threads.defaultCores
        // 超过 6 线程在手机上收益递减，发热反而拖低持续吞吐
        return cpuCores.coerceAtMost(rules.threads.maxCores).coerceAtLeast(1)
    }

    /**
     * MNN 的 GPU backend 是 OpenCL，但 Android 没有公开的 OpenCL 查询 API。
     * 只能用 GLES 版本 + GL_RENDERER 做保守推断，推不出来就走 CPU。
     */
    private fun canUseGpu(profile: DeviceProfile): Boolean {
        val glesMajor = profile.glesVersion.substringBefore('.').toIntOrNull() ?: 0
        if (glesMajor < rules.gpu.minGlesMajor) return false
        val renderer = profile.gpuRenderer ?: return false
        return rules.gpu.supportedRendererPrefixes.any { renderer.startsWith(it, ignoreCase = true) }
    }

    /** 内存紧张时用 mmap 降峰值，但需要足够的可用存储放缓存目录。 */
    private fun shouldUseMmap(profile: DeviceProfile): Boolean {
        val memTight = profile.totalMemMb in 1 until rules.mmap.enabledWhenTotalMemMbBelow
        val storageEnough = profile.freeStorageMb > rules.mmap.enabledWhenFreeStorageMbAbove
        return memTight && storageEnough
    }

    private fun formatGb(totalMemMb: Long): String {
        val gb = totalMemMb / 1024.0
        // 固定 Locale.US：部分区域的 decimal separator 是逗号，会让 reason 文案变成 "7,5GB"
        return String.format(java.util.Locale.US, "%.1fGB", gb)
    }

    companion object {
        /** 系统占用补偿：实测 totalMem 比标称少 0.4–0.8GB，取中值 */
        const val RESERVED_MEM_GB = 0.6

        /** 规则表为空时的兜底档位，不抛异常 */
        const val DEFAULT_NOMINAL_GB = 8

        private val json = Json { ignoreUnknownKeys = true }

        fun parseRules(raw: String): RecommendRules = json.decodeFromString(raw)
    }
}
