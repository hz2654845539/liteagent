package com.liteagent.app.benchmark

import com.liteagent.app.engine.LlmEngine
import java.io.File

/** 一次跑分的单条结果。指标全部来自 MNN 的 LlmContext，不是自己掐表。 */
data class BenchmarkRun(
    val promptName: String,
    val prompt: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val ttftMs: Long,
    val prefillMs: Long,
    val decodeMs: Long
) {
    val decodeTps: Float
        get() = if (decodeMs > 0) generatedTokens * 1000f / decodeMs else 0f

    val prefillTps: Float
        get() = if (prefillMs > 0) promptTokens * 1000f / prefillMs else 0f
}

data class BenchmarkReport(
    val modelId: String,
    val threads: Int,
    val useGpu: Boolean,
    val loadMs: Long,
    val peakMemMb: Long,
    val runs: List<BenchmarkRun>,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 复制为 Markdown 表格，方便直接贴进 README */
    fun toMarkdown(): String = buildString {
        appendLine("| Prompt | 输入 token | 输出 token | 首 token (ms) | prefill (tok/s) | decode (tok/s) |")
        appendLine("|---|---|---|---|---|---|")
        runs.forEach { r ->
            appendLine(
                "| ${r.promptName} | ${r.promptTokens} | ${r.generatedTokens} | " +
                    "${r.ttftMs} | ${fmt(r.prefillTps)} | ${fmt(r.decodeTps)} |"
            )
        }
        appendLine()
        appendLine("模型加载 ${loadMs} ms · 内存峰值 ${peakMemMb} MB · 线程 $threads · GPU ${if (useGpu) "开" else "关"}")
    }

    fun toJson(): String = buildString {
        append("{")
        append("\"modelId\":\"").append(modelId).append("\",")
        append("\"threads\":").append(threads).append(",")
        append("\"useGpu\":").append(useGpu).append(",")
        append("\"loadMs\":").append(loadMs).append(",")
        append("\"peakMemMb\":").append(peakMemMb).append(",")
        append("\"timestamp\":").append(timestamp).append(",")
        append("\"runs\":[")
        runs.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{")
            append("\"prompt\":\"").append(r.promptName).append("\",")
            append("\"promptTokens\":").append(r.promptTokens).append(",")
            append("\"generatedTokens\":").append(r.generatedTokens).append(",")
            append("\"ttftMs\":").append(r.ttftMs).append(",")
            append("\"prefillMs\":").append(r.prefillMs).append(",")
            append("\"decodeMs\":").append(r.decodeMs).append(",")
            append("\"decodeTps\":").append(fmt(r.decodeTps)).append(",")
            append("\"prefillTps\":").append(fmt(r.prefillTps))
            append("}")
        }
        append("]}")
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
}

/**
 * 跑分执行器。
 *
 * 固定三条 prompt（短/中/长），目的是**可比**：不同机型、不同线程数、开关 GPU，
 * 只要 prompt 不变，数字就能横向对比。换 prompt 就等于换了一把尺子。
 */
class BenchmarkRunner(private val engine: LlmEngine) {

    suspend fun run(
        modelId: String,
        threads: Int,
        useGpu: Boolean,
        onProgress: (index: Int, total: Int) -> Unit = { _, _ -> }
    ): BenchmarkReport {
        val loadMs = engine.perf().loadMs
        val runs = ArrayList<BenchmarkRun>()

        PROMPTS.forEachIndexed { index, spec ->
            onProgress(index, PROMPTS.size)
            engine.reset()
            try {
                engine.generate(spec.prompt, maxTokens = spec.maxTokens).collect { /* 消费掉流 */ }
            } catch (t: Throwable) {
                // 单条失败不影响其余条目，跑分页要能给出"这条跑失败了"而不是整页空白
            }
            val p = engine.perf()
            runs += BenchmarkRun(
                promptName = spec.name,
                prompt = spec.prompt,
                promptTokens = p.promptTokens,
                generatedTokens = p.genTokens,
                ttftMs = p.ttftMs,
                prefillMs = p.prefillMs,
                decodeMs = p.decodeMs
            )
        }
        onProgress(PROMPTS.size, PROMPTS.size)

        return BenchmarkReport(
            modelId = modelId,
            threads = threads,
            useGpu = useGpu,
            loadMs = loadMs,
            peakMemMb = MemoryProbe.peakRssMb(),
            runs = runs
        )
    }

    private data class PromptSpec(val name: String, val prompt: String, val maxTokens: Int)

    private companion object {
        val PROMPTS = listOf(
            PromptSpec("短", "你好", 64),
            PromptSpec("中", "用三句话解释什么是端侧推理。", 128),
            PromptSpec(
                "长",
                "请说明在手机上运行大语言模型时，内存带宽为什么会成为瓶颈，并举例。",
                256
            )
        )
    }
}

/**
 * 进程峰值内存。
 *
 * 为什么用 VmHWM 而不是 Java 堆：端侧 LLM 的权重和 KV cache 全在 native 侧分配，
 * `Runtime.maxMemory()` 完全反映不出来。VmHWM 是内核记录的历史峰值 RSS，才是
 * 判断"会不会被 LMK 杀掉"的依据。
 */
object MemoryProbe {
    fun peakRssMb(): Long = try {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.div(1024)
            ?: 0L
    } catch (t: Throwable) {
        0L
    }
}
