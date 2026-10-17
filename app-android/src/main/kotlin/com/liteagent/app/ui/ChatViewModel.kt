package com.liteagent.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liteagent.app.benchmark.BenchmarkReport
import com.liteagent.app.benchmark.BenchmarkRunner
import com.liteagent.app.capability.DeviceProfiler
import com.liteagent.app.capability.RecommendEngine
import com.liteagent.app.capability.Recommendation
import com.liteagent.app.capability.modelOrNull
import com.liteagent.app.data.ModelDownloader
import com.liteagent.app.data.ModelEntry
import com.liteagent.app.data.ModelStore
import com.liteagent.app.data.DocMeta
import com.liteagent.app.data.DocumentStore
import com.liteagent.app.data.Preset
import com.liteagent.app.data.PresetStore
import com.liteagent.app.data.Session
import com.liteagent.app.data.SessionMeta
import com.liteagent.app.data.SessionStore
import com.liteagent.app.engine.LlmEngine
import com.liteagent.app.engine.PerfSnapshot
import com.liteagent.app.rag.RagEngine
import com.liteagent.app.rag.RagHit
import com.liteagent.app.rag.TextChunker
import com.liteagent.app.tool.SkillRouter
import com.liteagent.app.tool.ToolCallParser
import com.liteagent.app.tool.ToolRouter
import com.liteagent.app.tool.ToolSpec
import com.liteagent.shared.ChatMessage
import com.liteagent.shared.ChatRole
import com.liteagent.shared.DeviceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ChatUiState(
    val profile: DeviceProfile? = null,
    val recommendation: Recommendation? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val modelSizeMb: Long = 0,
    val modelDownloaded: Boolean = false,
    val downloading: Boolean = false,
    val downloadPercent: Int = 0,
    val engineReady: Boolean = false,
    val loading: Boolean = false,
    val generating: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val perf: PerfSnapshot? = null,
    val error: String? = null,
    val showDevicePanel: Boolean = true,
    val benchmarking: Boolean = false,
    val benchmark: BenchmarkReport? = null,
    val benchmarkProgress: Int = 0,
    val benchmarkTotal: Int = 0,
    val documents: List<DocMeta> = emptyList(),
    val importing: Boolean = false,
    /** 清单里所有可选模型。推荐是默认值，不是强制值。 */
    val availableModels: List<ModelEntry> = emptyList(),
    val sessions: List<SessionMeta> = emptyList(),
    val currentSessionId: String? = null,
    val presetId: String = "assistant",
    /** 仅检索模式：不调用模型，直接返回原文段落。模型跑不动时的兜底。 */
    val retrievalOnly: Boolean = false,
    /** 实际加载时用的线程数。跑分要按这个值记录，不能用推荐值——调优实验时两者会不一致 */
    val loadedThreads: Int = 0,
    /** 输入框内容提升到 VM：OCR 识别完要能回填，UI 本地状态做不到 */
    val draft: String = "",
    /** 图片识别中 */
    val ocrRunning: Boolean = false
)

/**
 * 聊天页状态机。
 *
 * 一条约束贯穿全部：**引擎的所有阻塞调用都在 IO 线程**，
 * UI 只消费 StateFlow，绝不在 JNI 回调线程里碰 Compose 状态。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val catalog = ModelStore.loadCatalog(app)

    /**
     * 当前选中的模型条目。
     *
     * 必须按推荐结果取，不能写死第一个：清单里有 0.5B / 1.5B / 3B 三个，
     * 8GB 机推荐 1.5B 却去下载 0.5B 的话，"设备自适应"就只是一块显示用的牌子。
     */
    @Volatile
    private var currentEntry: ModelEntry? = null

    private fun chooseEntry(rec: Recommendation?): ModelEntry? {
        // 优先用上次手动选的（且必须已下载完整），否则按推荐匹配，再不行取最小的
        val savedId = prefs.getString(KEY_SELECTED_MODEL, null)
        savedId?.let { id ->
            catalog.models.firstOrNull { it.id == id }?.let { saved ->
                if (store.isReady(saved)) return saved
            }
        }
        val wantId = rec.modelOrNull()?.modelId
        return catalog.models.firstOrNull { it.id == wantId }
            ?: catalog.models.minByOrNull { it.totalBytes }
    }
    private val downloader = ModelDownloader(store)
    private val engine = LlmEngine()
    private val toolRouter = ToolRouter(app)
    private val documentStore = DocumentStore(app)
    private val ragEngine = RagEngine()
    private val sessionStore = SessionStore(app)
    private val imageTextExtractor = com.liteagent.app.vision.ImageTextExtractor()

    /** 用户手动选的模型要记住。不记的话每次重启都退回推荐档，等于白选。 */
    private val prefs = app.getSharedPreferences("liteagent", android.content.Context.MODE_PRIVATE)

    /** 当前会话。每次消息变动后写回磁盘，退出 App 不丢。 */
    @Volatile
    private var currentSession: Session? = null

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            // 读 assets 规则 + 检查模型文件 + 重建文档索引，都是磁盘 IO，放 IO 线程
            val snapshot = withContext(Dispatchers.IO) {
                val p = DeviceProfiler(getApplication()).collect()
                val rules = runCatching {
                    getApplication<Application>().assets.open("rules/recommend-rules.json")
                        .bufferedReader().use { it.readText() }
                }.map { RecommendEngine.parseRules(it) }.getOrNull()

                val rec = if (rules != null) {
                    RecommendEngine(rules).recommend(p)
                } else {
                    Recommendation.Unsupported("规则文件缺失，无法给出推荐")
                }

                indexDocuments()
                val chosen = chooseEntry(rec)
                currentEntry = chosen

                // 恢复最近一次会话；第一次启动就建一个空的
                val session = sessionStore.list().firstOrNull()
                    ?.let { sessionStore.load(it.id) }
                    ?: sessionStore.create()
                currentSession = session

                BootstrapSnapshot(
                    profile = p,
                    recommendation = rec,
                    modelDownloaded = chosen != null && store.isReady(chosen),
                    documents = documentStore.listDocs(),
                    entry = chosen,
                    session = session,
                    sessions = sessionStore.list()
                )
            }

            _state.update {
                it.copy(
                    profile = snapshot.profile,
                    recommendation = snapshot.recommendation,
                    modelId = snapshot.entry?.id,
                    modelName = snapshot.entry?.displayName,
                    modelSizeMb = snapshot.entry?.totalBytes?.div(1024 * 1024) ?: 0,
                    modelDownloaded = snapshot.modelDownloaded,
                    documents = snapshot.documents,
                    availableModels = catalog.models,
                    messages = snapshot.session.messages,
                    currentSessionId = snapshot.session.id,
                    presetId = snapshot.session.presetId,
                    sessions = snapshot.sessions
                )
            }
        }
    }

    private data class BootstrapSnapshot(
        val profile: DeviceProfile,
        val recommendation: Recommendation,
        val modelDownloaded: Boolean,
        val documents: List<DocMeta>,
        val entry: ModelEntry?,
        val session: Session,
        val sessions: List<SessionMeta>
    )

    /** 全量重建 BM25 索引。文档量在几十篇量级，重建成本远低于维护增量索引的复杂度。 */
    private fun indexDocuments() {
        val chunker = TextChunker()
        val allChunks = documentStore.listDocs().flatMap { meta ->
            documentStore.readContent(meta.id)
                ?.let { chunker.chunk(meta.id, meta.name, it) }
                ?: emptyList()
        }
        ragEngine.rebuild(allChunks)
    }

    fun importDocument(uri: android.net.Uri) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            documentStore.import(getApplication<Application>().contentResolver, uri)
                .onSuccess { meta ->
                    withContext(Dispatchers.IO) { indexDocuments() }
                    _state.update {
                        it.copy(importing = false, documents = documentStore.listDocs())
                    }
                    // 导入后立刻提示分块结果，用户能判断是不是切得合理
                    _state.update {
                        it.copy(error = "已导入「${meta.name}」，切成 ${meta.chunkCount} 个片段")
                    }
                }
                .onFailure { t ->
                    _state.update { it.copy(importing = false, error = t.message ?: "导入失败") }
                }
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            documentStore.delete(id)
            withContext(Dispatchers.IO) { indexDocuments() }
            _state.update { it.copy(documents = documentStore.listDocs()) }
        }
    }

    /**
     * 手动切换模型。
     *
     * 推荐是默认值不是强制值：用户可能想用更小的模型省空间、或者主动试更大的。
     * 切换时要先释放引擎——LlmEngine 是独占资源，同时加载两个模型会把内存打爆。
     */
    fun selectModel(id: String) {
        val target = catalog.models.firstOrNull { it.id == id } ?: return
        if (target.id == currentEntry?.id) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                engine.release()
                currentEntry = target
                prefs.edit().putString(KEY_SELECTED_MODEL, target.id).apply()
            }
            _state.update {
                it.copy(
                    modelId = target.id,
                    modelName = target.displayName,
                    modelSizeMb = target.totalBytes / (1024 * 1024),
                    modelDownloaded = store.isReady(target),
                    engineReady = false,
                    messages = emptyList(),
                    perf = null,
                    error = null
                )
            }
        }
    }

    fun downloadModel() {
        val e = currentEntry ?: return
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, error = null) }
        viewModelScope.launch {
            downloader.download(e) { done, total ->
                val percent = if (total > 0) (done * 100 / total).toInt() else 0
                _state.update { it.copy(downloadPercent = percent) }
            }.onSuccess {
                _state.update { it.copy(downloading = false, downloadPercent = 100, modelDownloaded = true) }
            }.onFailure { t ->
                _state.update { it.copy(downloading = false, error = t.message ?: "下载失败") }
            }
        }
    }

    /**
     * threadsOverride / reuseKv 只给实验用：覆盖推荐规则里的线程数和 KV 复用开关。
     *
     * reuse_kv 默认 false，意味着每轮对话都要把 system + 全部历史重新 prefill 一遍。
     * 手机上这是最贵的一笔开销（prefill 15 t/s，1000 字要 60 秒）。
     */
    fun loadModel(threadsOverride: Int? = null, reuseKv: Boolean = false) {
        val e = currentEntry ?: return
        if (!store.isReady(e)) {
            _state.update { it.copy(error = "模型文件不完整，请先下载") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val rec = _state.value.recommendation.modelOrNull()
            val threads = threadsOverride ?: rec?.threads ?: 4
            val config = buildString {
                append("{")
                append("\"backend_type\":\"").append(if (rec?.useGpu == true) "opencl" else "cpu").append("\",")
                append("\"thread_num\":").append(threads).append(",")
                append("\"precision\":\"low\",")
                append("\"memory\":\"low\"")
                if (reuseKv) {
                    // 把 KV cache 复用打开：多轮对话只 prefill 新增部分
                    append(",\"reuse_kv\":true")
                }
                append("}")
            }
            // nativeCreate + Llm::load() 是同步阻塞的 JNI 调用，加载 531MB 权重在手机上
            // 要几百毫秒到数秒。放 Main 线程会 ANR。
            val result = withContext(Dispatchers.IO) {
                engine.init(store.configPath(e.id), config)
            }
            _state.update {
                it.copy(
                    loading = false,
                    engineReady = result.isSuccess,
                    loadedThreads = if (result.isSuccess) threads else 0,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun send(text: String) {
        val content = text.trim()
        // 仅检索模式不需要引擎，所以可用条件比"引擎就绪"宽一档
        val canSend = _state.value.engineReady || _state.value.retrievalOnly
        if (content.isEmpty() || _state.value.generating || !canSend) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = content,
            timestamp = System.currentTimeMillis()
        )
        val assistantId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
            id = assistantId,
            role = ChatRole.ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis()
        )

        _state.update {
            it.copy(
                messages = it.messages + userMessage + assistantMessage,
                generating = true,
                error = null
            )
        }

        viewModelScope.launch {
            // 仅检索模式：不碰模型，直接把命中的原文段落拼出来
            if (_state.value.retrievalOnly) {
                val hits = withContext(Dispatchers.IO) { ragEngine.retrieve(content) }
                val answer = ragEngine.buildExtractiveAnswer(content, hits)
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map {
                            if (it.id == assistantId) it.copy(content = answer) else it
                        },
                        generating = false
                    )
                }
                attachCitations(assistantId, answer, hits)
                persistSession()
                return@launch
            }

            // 技能匹配 + 文档检索都在 IO 上做，其中检索要走一遍全部块的打分
            val prepared = withContext(Dispatchers.IO) { preparePrompt(content) }

            // 第一轮：带上工具 schema，模型可能直接回答，也可能吐一个 JSON 调用
            val budget = if (_state.value.recommendation is Recommendation.Restricted) {
                MAX_TOKENS_EXTREME
            } else {
                MAX_TOKENS
            }
            val firstPass = generateInto(
                buildMessages(prepared.system, prepared.prompt),
                assistantId,
                budget = budget
            )
            val call = ToolCallParser.parse(firstPass)

            if (call != null) {
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map {
                            if (it.id == assistantId) it.copy(content = "正在调用 ${call.tool}…") else it
                        }
                    )
                }

                // 工具执行是磁盘/系统调用，不能在 Main 线程
                val feedback = withContext(Dispatchers.IO) { toolRouter.execute(call) }

                // 第二轮：把执行结果回灌，让模型用人话总结
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map {
                            if (it.id == assistantId) it.copy(content = "") else it
                        }
                    )
                }
                val followUp = "用户说：$content\n工具执行结果：$feedback\n请用一句中文告诉用户结果。"
                // 工具回灌这一轮不带历史：第一轮吐的 JSON 还在上下文里，
                // 带着它模型很容易继续吐 JSON 而不是用人话总结
                generateInto(
                    listOf(
                        "system" to PresetStore.find(_state.value.presetId).systemPrompt,
                        "user" to followUp
                    ),
                    assistantId,
                    reset = true
                )
            } else if (prepared.hits.isNotEmpty()) {
                attachCitations(assistantId, firstPass, prepared.hits)
            }

            _state.update { it.copy(generating = false, perf = engine.perf()) }
            persistSession()
        }
    }

    private data class PreparedPrompt(
        val prompt: String,
        val system: String,
        val hits: List<RagHit>
    )

    /**
     * 技能匹配 + RAG 检索。
     *
     * 检索没命中时**不拼资料**——宁可当普通对话，也不要把无关片段塞进上下文，
     * 那既浪费 prefill（手机上很贵），又会诱导模型硬套资料。
     */
    /**
     * 组装发给模型的消息序列。
     *
     * 之前只发 system + 当前问题，导致多轮指代完全失效——用户说"再说一遍要点"，
     * 模型看不到上一轮说过什么，只能瞎猜。界面上看起来是多轮对话，实际每轮都是孤立的。
     *
     * 加历史要付 prefill 代价（这台设备 15 tok/s，多 300 字就多 20 秒），
     * 所以按字符预算从最近往前取，超了就丢最早的。
     */
    private fun buildMessages(
        system: String,
        currentPrompt: String,
        charBudget: Int = HISTORY_CHAR_BUDGET
    ): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        if (system.isNotBlank()) result.add("system" to system)

        val all = _state.value.messages
        // 尾部有一条空的 assistant 占位（等流式填充），先剥掉；
        // 再去掉最后一条用户消息——它就是 currentPrompt，下面会重新加，不然会发两遍
        val trimmed = all.dropLastWhile { it.content.isBlank() }
        val lastUserIdx = trimmed.indexOfLast { it.role == com.liteagent.shared.ChatRole.USER }
        val history = trimmed.filterIndexed { idx, msg ->
            idx != lastUserIdx && msg.content.isNotBlank()
        }

        val picked = ArrayDeque<Pair<String, String>>()
        var used = 0
        for (msg in history.asReversed()) {
            if (msg.content.isBlank()) continue
            if (used + msg.content.length > charBudget) break
            // MNN 的 prompt_template 认的是小写 role 名
            picked.addFirst(msg.role.name.lowercase() to msg.content)
            used += msg.content.length
        }
        result.addAll(picked)
        result.add("user" to currentPrompt)
        android.util.Log.i(
            "MultiTurn",
            "buildMessages size=${result.size} " +
                "detail=${result.joinToString(",") { "${it.first}:${it.second.length}" }}"
        )
        return result
    }

    private suspend fun preparePrompt(input: String): PreparedPrompt {
        val preset = PresetStore.find(_state.value.presetId)
        // 极限档（0.5B）不注入工具 schema：实测它会照着里面的示例逐行复述，
        // 而不是回答问题。参数太少的模型分不清"示例"和"该输出的内容"。
        val useTools = _state.value.recommendation !is Recommendation.Restricted
        var system = if (useTools) preset.systemPrompt + "\n\n" + ToolSpec.prompt
        else preset.systemPrompt

        val skill = SkillRouter.match(input)
        val skillText = skill?.let { SkillRouter.load(getApplication(), it) }
        if (skillText != null) {
            system += "\n\n" + skillText
        }

        val hits = ragEngine.retrieve(input)
        if (hits.isEmpty()) return PreparedPrompt(input, system, emptyList())

        system += "\n\n用户设备里有本地资料，回答必须只依据下面提供的资料，并在句末标注来源编号。"
        return PreparedPrompt(ragEngine.buildPrompt(input, hits), system, hits)
    }

    private fun attachCitations(assistantId: String, answer: String, hits: List<RagHit>) {
        val citations = ragEngine.extractCitations(answer, hits)
        if (citations.isEmpty()) return
        _state.update { s ->
            s.copy(
                messages = s.messages.map {
                    if (it.id == assistantId) it.copy(citations = citations) else it
                }
            )
        }
    }

    /**
     * 跑一轮生成并把 token 追加到指定消息上。
     * @param reset 是否清空 KV cache。工具回灌的第二轮要清，否则第一轮的 JSON
     *              留在历史里，模型很容易继续吐 JSON。
     */
    private suspend fun generateInto(
        messages: List<Pair<String, String>>,
        assistantId: String,
        reset: Boolean = false,
        budget: Int = MAX_TOKENS
    ): String {
        if (reset) engine.reset()
        val buffer = StringBuilder()
        engine.generate(messages, budget)
            .catch { t ->
                _state.update { s -> s.copy(error = t.message ?: "生成失败") }
            }
            .collect { token ->
                buffer.append(token)
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map {
                            if (it.id == assistantId) it.copy(content = it.content + token) else it
                        }
                    )
                }
            }
        return buffer.toString()
    }

    fun stop() {
        engine.stop()
    }

    /**
     * 跑一轮固定测试集。
     *
     * 跑分期间必须独占引擎：encoder 状态会被 reset，和正在进行的对话冲突。
     * 所以这里不检查 generating，而是靠 UI 禁用入口（引擎只有一个实例）。
     */
    fun runBenchmark() {
        if (!_state.value.engineReady || _state.value.benchmarking) return
        val rec = _state.value.recommendation.modelOrNull()

        _state.update {
            it.copy(benchmarking = true, benchmark = null, benchmarkProgress = 0, error = null)
        }

        viewModelScope.launch {
            // 用实际加载的线程数，不是推荐值：调优实验时要能看出 2/3/4/5 线程的差异
            val threads = _state.value.loadedThreads.takeIf { it > 0 } ?: rec?.threads ?: 4
            val report = withContext(Dispatchers.IO) {
                BenchmarkRunner(engine).run(
                    modelId = currentEntry?.id ?: "unknown",
                    threads = threads,
                    useGpu = rec?.useGpu == true
                ) { index, total ->
                    _state.update { it.copy(benchmarkProgress = index, benchmarkTotal = total) }
                }.also { result ->
                    // 落盘一份，方便 adb pull 做离线对比；UI 上的「导出 JSON」是给用户用的
                    runCatching {
                        val dir = File(getApplication<Application>().filesDir, "benchmark")
                            .apply { mkdirs() }
                        val file = File(dir, "bench_${threads}t_${System.currentTimeMillis()}.json")
                        file.writeText(result.toJson())
                        android.util.Log.i("Benchmark", "saved ${file.absolutePath}")
                    }
                }
            }
            _state.update { it.copy(benchmarking = false, benchmark = report) }
        }
    }

    fun toggleDevicePanel() {
        _state.update { it.copy(showDevicePanel = !it.showDevicePanel) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun setDraft(text: String) {
        _state.update { it.copy(draft = text) }
    }

    /**
     * 选一张图 → OCR → 把文字填进输入框。
     *
     * 填输入框而不是直接发送：OCR 会认错字（尤其手写和表格），
     * 让用户看一眼再发，比替他去问更靠谱。
     */
    fun importImage(uri: android.net.Uri) {
        if (_state.value.ocrRunning) return
        _state.update { it.copy(ocrRunning = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                imageTextExtractor.extract(getApplication(), uri)
            }
            result.fold(
                onSuccess = { text ->
                    val existing = _state.value.draft
                    val merged = buildString {
                        if (existing.isNotBlank()) {
                            append(existing.trim())
                            append("\n\n")
                        }
                        append("图片里的文字：\n")
                        append(text.take(MAX_OCR_CHARS))
                        if (text.length > MAX_OCR_CHARS) append("\n…（已截断）")
                    }
                    _state.update {
                        it.copy(ocrRunning = false, draft = merged, error = null)
                    }
                },
                onFailure = { err ->
                    _state.update { s ->
                        s.copy(ocrRunning = false, error = "图片识别失败：${err.message}")
                    }
                }
            )
        }
    }

    fun clearChat() {
        engine.reset()
        _state.update { it.copy(messages = emptyList(), perf = null) }
        persistSession()
    }

    /** 把当前消息写回磁盘。生成结束、切换预设、清空对话后调用。 */
    private fun persistSession() {
        val base = currentSession ?: return
        val updated = base.copy(
            messages = _state.value.messages,
            presetId = _state.value.presetId
        )
        currentSession = updated
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.save(updated)
            _state.update { it.copy(sessions = sessionStore.list()) }
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val session = withContext(Dispatchers.IO) {
                engine.reset()
                sessionStore.create(_state.value.presetId)
            }
            currentSession = session
            _state.update {
                it.copy(
                    messages = emptyList(),
                    currentSessionId = session.id,
                    sessions = sessionStore.list(),
                    perf = null,
                    error = null
                )
            }
        }
    }

    fun switchSession(id: String) {
        if (id == _state.value.currentSessionId) return
        viewModelScope.launch {
            val session = withContext(Dispatchers.IO) {
                // 切走之前先把当前会话落盘，否则这段还没持久化的对话会丢
                currentSession?.let { base ->
                    sessionStore.save(
                        base.copy(
                            messages = _state.value.messages,
                            presetId = _state.value.presetId
                        )
                    )
                }
                sessionStore.load(id)?.also { engine.reset() }
            } ?: return@launch
            currentSession = session
            _state.update {
                it.copy(
                    messages = session.messages,
                    currentSessionId = session.id,
                    presetId = session.presetId,
                    perf = null,
                    error = null,
                    sessions = sessionStore.list()
                )
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                sessionStore.delete(id)
                sessionStore.list()
            }
            if (id != _state.value.currentSessionId) {
                _state.update { it.copy(sessions = remaining) }
                return@launch
            }
            // 删的是当前会话：切到最近一个，一个都没有就新建
            val next = withContext(Dispatchers.IO) {
                remaining.firstOrNull()?.let { sessionStore.load(it.id) } ?: sessionStore.create()
            }
            currentSession = next
            _state.update {
                it.copy(
                    messages = next.messages,
                    currentSessionId = next.id,
                    presetId = next.presetId,
                    sessions = sessionStore.list(),
                    perf = null
                )
            }
        }
    }

    /** 生成当前会话的 Markdown 文本，交给 UI 去分享或复制 */
    fun exportCurrent(): String {
        val session = currentSession ?: return ""
        return sessionStore.exportMarkdown(session.copy(messages = _state.value.messages))
    }

    /** 仅检索模式开关。模型加载不了时，用户至少还能查文档。 */
    fun toggleRetrievalOnly(enabled: Boolean) {
        _state.update { it.copy(retrievalOnly = enabled) }
    }

    fun setPreset(id: String) {
        if (id == _state.value.presetId) return
        _state.update { it.copy(presetId = id) }
        viewModelScope.launch {
            // 换人格等于换上下文。KV cache 里还留着旧人格的对话，
            // 不清的话模型会带着"我是翻译"的历史去扮演"资深工程师"，输出会飘
            withContext(Dispatchers.IO) { engine.reset() }
            persistSession()
        }
    }

    /** 当前预设，UI 用来显示名字 */
    fun currentPreset(): Preset = PresetStore.find(_state.value.presetId)

    override fun onCleared() {
        super.onCleared()
        engine.release()
    }

    private companion object {
        const val KEY_SELECTED_MODEL = "selected_model"
        const val MAX_TOKENS = 512

        /**
         * 极限档的输出上限。
         *
         * 0.5B 有个顽固毛病：不会自己停在 eos，而是把同一段话循环说下去直到撞上限。
         * 512 token 在这台设备上要 80 秒，用户看到的是"AI 一直重复同一句"。
         * 192 大约是 2-3 句话，既够用又不会等到崩。
         */
        const val MAX_TOKENS_EXTREME = 192

        /** OCR 结果的上限。一张 A4 满页中文约 1500 字，2000 够用；再多会把上下文吃光 */
        const val MAX_OCR_CHARS = 2000

        /**
         * 历史消息的字符预算。
         *
         * 这个数字是实测调出来的，不是拍的：
         *   无历史（单轮）  ttft ≈ 1.7 s
         *   6 条历史 600 字  ttft ≈ 17.0 s
         * 历史长度直接按比例换成首 token 延迟，这台设备 prefill 只有 25 tok/s。
         *
         * KV 复用救不了这件事：MNN 的 reuse_kv 只在 attention_mode == 10 时生效，
         * 且 replayKeptHistory 是给 linear attention 用的（做法是重新 prefill，
         * 不是真复用）。实测开关两组数据完全一致，走不通。
         *
         * 所以只能砍预算。300 字约 200 token，prefill ≈ 8 秒，是"记得住上一轮"和
         * "别让用户等太久"之间的折中。
         */
        const val HISTORY_CHAR_BUDGET = 300
    }
}
