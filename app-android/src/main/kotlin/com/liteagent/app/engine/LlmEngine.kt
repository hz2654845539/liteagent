package com.liteagent.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** JNI 回调接口。返回 true 表示请求停止生成。 */
fun interface TokenCallback {
    fun onToken(text: String): Boolean
}

/** 一次生成的性能快照，字段全部来自 MNN 的 LlmContext。 */
data class PerfSnapshot(
    val loadMs: Long,
    val prefillMs: Long,
    val decodeMs: Long,
    val ttftMs: Long,
    val promptTokens: Int,
    val genTokens: Int
) {
    /** decode 吞吐（tok/s），denominator 为 0 时返回 0 而不是 Infinity */
    val decodeTps: Float
        get() = if (decodeMs > 0) genTokens * 1000f / decodeMs else 0f

    val prefillTps: Float
        get() = if (prefillMs > 0) promptTokens * 1000f / prefillMs else 0f
}

/**
 * MNN LLM 引擎封装。
 *
 * 生命周期：init → generate（可多次）→ release。
 * 线程约束：generate 必须在后台线程调用（内部是阻塞式的），release 必须等所有生成结束。
 */
class LlmEngine {

    @Volatile
    private var handle: Long = 0L

    val isReady: Boolean
        get() = handle != 0L

    /**
     * 加载模型。
     * @param configPath 模型目录下的 config.json 绝对路径（不是 .mnn 文件名）
     * @param configJson 覆盖配置，如 {"threads":4,"backend":"cpu"}；传空串使用模型自带配置
     */
    fun init(configPath: String, configJson: String = ""): Result<Unit> = runCatching {
        check(handle == 0L) { "引擎已初始化，先 release 再 init" }
        val h = nativeCreate(configPath, configJson)
        if (h == 0L) error("模型加载失败：$configPath")
        handle = h
    }

    /**
     * 流式生成（多轮）。
     *
     * 内部实现：先做一次 prefill（不产出 token），再逐 token 解码。
     * 这样每个 token 之间都能检查停止标志，点"停止"后最坏延迟是一个 token 的解码时间。
     *
     * @param messages [(role, content)]，按时间顺序。MNN 会按模型自带的
     *   prompt_template 逐条套成 <|im_start|>role\ncontent<|im_end|>。
     */
    fun generate(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512
    ): Flow<String> = callbackFlow {
        val h = handle
        if (h == 0L) {
            close(IllegalStateException("引擎未初始化"))
            return@callbackFlow
        }

        val stopRequested = AtomicBoolean(false)
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()

        val worker = launch(Dispatchers.IO) {
            try {
                nativeGenerate(h, roles, contents, maxTokens, TokenCallback { text ->
                    trySend(text)
                    // 返回 true = 让 C++ 侧退出解码循环
                    stopRequested.get()
                })
            } finally {
                close()
            }
        }

        awaitClose {
            stopRequested.set(true)
            nativeStop(h)
            worker.cancel()
        }
    }.buffer(Channel.UNLIMITED)

    /** 单轮快捷方式，内部转成只有 system + user 的两条 messages */
    fun generate(
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int = 512
    ): Flow<String> = generate(
        buildList {
            if (systemPrompt.isNotBlank()) add("system" to systemPrompt)
            add("user" to prompt)
        },
        maxTokens
    )

    /** 主动停止当前生成。可以从主线程调用。 */
    fun stop() {
        val h = handle
        if (h != 0L) nativeStop(h)
    }

    fun perf(): PerfSnapshot {
        val h = handle
        if (h == 0L) return PerfSnapshot(0, 0, 0, 0, 0, 0)
        val v = nativePerf(h) ?: return PerfSnapshot(0, 0, 0, 0, 0, 0)
        return PerfSnapshot(
            loadMs = v[0] / 1000,
            prefillMs = v[1] / 1000,
            decodeMs = v[2] / 1000,
            ttftMs = v[3] / 1000,
            promptTokens = v[4].toInt(),
            genTokens = v[5].toInt()
        )
    }

    fun reset() {
        val h = handle
        if (h != 0L) nativeReset(h)
    }

    fun release() {
        val h = handle
        if (h != 0L) {
            nativeRelease(h)
            handle = 0L
        }
    }

    private external fun nativeCreate(configPath: String, configJson: String): Long
    private external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        callback: TokenCallback
    ): Int
    private external fun nativeStop(handle: Long)
    private external fun nativePerf(handle: Long): LongArray?
    private external fun nativeReset(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            // 顺序有依赖：libMNN.so 必须先加载
            System.loadLibrary("MNN")
            System.loadLibrary("liteagent_llm")
        }
    }
}
