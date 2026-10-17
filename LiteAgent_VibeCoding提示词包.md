# LiteAgent · MNN 端侧 AI 项目 Vibe Coding 提示词包 v2

> **v2 变更**（2026-10-17）：
> 1. 新增「设备能力探测 + 模型分级推荐」——同一份 APK 在不同机型上自动选模型与参数
> 2. 新增独立 Benchmark 阶段（prefill / decode / 首token / 内存）
> 3. RAG 升级为「BM25 → 向量」两步走，支持混合检索
> 4. 引入极简 Skills（3 个 Markdown 工作流），不做完整引擎
> 5. 后端锁定 Python FastAPI；阶段从 4 个细化为 6 个
>
> **用法**：第一部分整段贴进 `AGENTS.md` 或 `.cursor/rules/project.mdc`（长期规则）；第二部分按阶段逐条投喂，一次只做一个阶段。
> **核心原则**：先核对官方源码再写码；每步先给方案，确认后落码。

---

## 第一部分 · 主规则（贴进 AGENTS.md 或 .cursor/rules/project.mdc）

```markdown
# 角色
你是我的 Android 端侧 AI 工程师搭档，负责在一个单仓（monorepo）里实现 LiteAgent：
一个完全离线、数据不出设备的手机 AI 助理。

# 产品定义（写代码前先理解）
一句话：断网也能用的手机 AI 助理——能聊天、能翻手机文件回答问题、能替你建日历设提醒。
三个卖点必须成立：离线可用 / 数据不出设备 / 零 token 成本。

# 功能范围（严格按此，不许自行扩张）
P0 必做：端侧对话（流式）、模型管理（下载/切换/删除/断点续传）、
        设备能力探测 + 模型与参数自动推荐
P1 核心：本地 RAG（先 BM25 后向量）、3 个工具调用（日历/提醒/剪贴板）
P2 加分：Benchmark 跑分页、3 个 Skills（Markdown 定义）
明确不做：账号体系、云同步、无障碍 UI 自动化、图像生成、语音唤醒、多云端 provider

# 技术栈（锁定，不要擅自更换）
- 客户端：Kotlin 2.x + Jetpack Compose，minSdk 26，targetSdk 35，仅 arm64-v8a
- 推理：MNN（LLM 走 MNN-LLM），JNI 封装，禁止自行重写推理内核
- 后端：Python 3.11 + FastAPI + SQLite
- 共享层：Kotlin Multiplatform（数据模型 + API 契约）
- 构建：Gradle Kotlin DSL

# 硬约束（违反即失败）
1. 任何 MNN 相关代码，必须先在本地 clone 的 MNN 仓库里检索确认
   （apps/Android/MnnLlmChat、include/MNN/expr/Transformer.hpp、llm 相关 JNI 封装），
   禁止凭记忆编造 API 名称、参数、返回类型。找不到就写「未确认」，不要猜。
2. 不要复制 MNN Chat 的 UI 层、业务层代码；只参考它的 native 集成方式与模型管理逻辑。
3. 每一步先输出 3-5 行方案（改哪些文件、为什么），等我确认后再写代码。
4. 单次改动不超过 3 个文件，避免大爆炸式重构。
5. 不允许留 TODO / NotImplementedError 后宣称完成；编译不过不算完成。
6. 所有推理相关代码必须有性能打点：模型加载耗时、prefill 耗时、首 token 延迟、
   decode 速度（tok/s）、内存峰值。统一走 PerfLogger。
7. 模型文件不进 APK，首次启动下载或本地导入，必须支持断点续传与校验和验证。
8. 不引入新依赖前先说明理由和体积代价。
9. 设备相关能力必须优雅降级：低端机不崩、高端机跑满，任何探测失败都有兜底值。
10. 工具调用禁止依赖模型原生 function calling（小模型能力弱），统一走
    「JSON schema 约束输出 + 客户端解析 + 失败重试一次」的方案。

# 代码规范
- 分层：ui / domain / data / engine / capability，engine 与 capability 不依赖 UI
- 协程 + Flow 做流式输出，禁止在回调里直接更新 UI
- 所有 JNI 资源必须有明确的 acquire/release 生命周期，禁止泄漏
- 关键路径写中文注释，解释「为什么」而不是「做了什么」
- 规则表（设备分级、模型推荐）必须外置为可配置常量，禁止散落在业务代码里

# 输出格式
每次回复按此结构：方案（3-5 行）→ 变更文件清单 → 完整代码 → 自检结果 → 下一步建议。
```

---

## 第二部分 · 阶段任务卡（按顺序投喂，一阶段一确认）

### 阶段 0 · 资料核对（必做）

```markdown
阶段 0：环境与资料核对。只做调研，不写业务代码。

1. clone MNN 仓库，定位并阅读：
   - apps/Android/MnnLlmChat 的目录结构与 native 集成方式
   - LLM 的 C++ 入口与 JNI 封装（session 创建、response 回调、release）
   - mnnconvert 的转换参数与量化选项
2. 确认 Qwen2.5 系列（0.5B / 1.5B / 3B）的 MNN 量化模型在魔搭是否存在：
   给出模型名、体积、量化精度（W4/W8）、推荐内存下限。做成一张表。
3. 给出「集成路径对比」：
   A. 用 MNN 官方预编译 .so + 自己写 JNI 封装
   B. 复用 MnnLlmChat 的 native 模块作为依赖
   对比工作量、风险、包体积，给推荐结论。
4. 列出「尚未确认」的技术点（不要猜，列出来等我核实）。

产出：docs/00-research.md
验收：我能照着它定集成方案，且没有一个编造的 API。
```

### 阶段 1 · 单仓骨架 + 端侧对话 + 设备探测

```markdown
阶段 1：跑通对话，并建立设备画像能力。

A. 单仓骨架（Gradle Kotlin DSL）：
   liteagent/
   ├── app-android/     # Android 应用
   ├── backend/         # FastAPI（本阶段只建空壳 + README）
   ├── shared/          # KMP（本阶段只放 ChatMessage 等基础模型）
   ├── tools/           # 模型转换/量化脚本
   └── docs/

B. MNN 集成（按阶段 0 选定方案），确保 arm64-v8a 可加载。

C. engine 层最小封装：
   - LlmEngine.init(modelDir): Boolean
   - LlmEngine.generate(prompt, onToken): Flow<String>
   - LlmEngine.release()
   要求流式、可中断、线程安全。

D. capability 层 · 设备能力探测（本阶段重点）：
   实现 DeviceProfiler.collect(): DeviceProfile
   采集字段见本文档第三部分，要求：
   - 每个字段独立 try-catch，取不到就给兜底值，绝不因探测失败崩溃
   - 不申请任何敏感权限（全部走公开 API）
   - 结果缓存到本地，支持手动刷新

E. UI：Compose 聊天页（消息列表 + 输入框 + 流式渲染 + 停止按钮）
   + 设置页里的「设备信息」卡片（展示 DeviceProfile）。

产出：可运行 APK + 聊天可用 + 设备信息页可见。
验收：真机加载 1.5B 模型成功，输入「你好」3 秒内出首 token；
      设备信息页正确显示芯片/内存/Android 版本/ABI/加速能力。
```

### 阶段 2 · 模型推荐引擎 + Benchmark

```markdown
阶段 2：让 App 根据设备自己选模型、选参数，并能跑分证明优化有效。

A. 模型推荐引擎（纯规则，外置配置）：
   输入 DeviceProfile，输出 ModelRecommendation：
   - 推荐模型（0.5B / 1.5B / 3B / 7B）
   - 量化精度（W4 / W8）
   - 线程数、上下文长度、是否启用 GPU
   规则表见第三部分，必须做成可配置常量，便于后续云端下发。

B. 推荐解释要可见：
   设置页显示「为什么推荐这个模型」，例如
   「你的设备 8GB 内存 · 骁龙 8 Gen 2 · 支持 Vulkan → 推荐 1.5B W4，开启 GPU 加速」。

C. Benchmark 页：
   - 固定测试集（3 条不同长度的 prompt）
   - 输出：模型加载耗时、prefill 耗时、首 token 延迟、decode tok/s、内存峰值
   - 结果表格化，可导出为 JSON / 复制为 Markdown（方便贴进 README）
   - 记录机型与模型信息，便于多设备对比

D. 按 Benchmark 结果反推一次参数调优：
   对比「CPU 线程数 4 vs 6」「GPU 开 vs 关」，把结论写进 docs/02-tuning.md。

产出：推荐引擎 + 跑分页 + 一份调优结论。
验收：同一份 APK 在 6GB 与 12GB 设备上推荐出不同模型；
      Benchmark 能稳定跑出 5 项指标并导出。
```

### 阶段 3 · 本地 RAG（两步走）

```markdown
阶段 3：让助理能翻手机文件回答问题。分两步，先跑通再加精度。

第 1 步（先做，必须能跑通）：
- 文档导入：支持 txt / md / pdf，用户选择目录或文件
- 分块：300-500 字，带 1 句重叠，保留标题层级
- 检索：BM25 / 关键词 + 中文分词（不引入 embedding 模型，零额外依赖）
- 上下文组装：Top-3 片段 + 问题，注意 token 预算截断
- 回答必须标注来源：「引用自 xxx.md 第 3 段」，点击可跳转原文

第 2 步（第 1 步稳定后再做）：
- 引入轻量 embedding 模型（如 all-MiniLM 或 bge-small 的 MNN/ONNX 版本）
- 向量库：先用内存 + 本地文件持久化，不引入 FAISS 等重型依赖
- 混合检索：向量相似度 + 关键词命中加权融合
- Retriever 抽象成接口，BM25 与向量两种实现可切换

产出：可用的本地知识库问答 + 来源标注。
验收：10 份文档、500 个分块下，问「我上次方案的量化精度是多少」能召回正确片段；
      回答带来源，且点开能定位原文。
```

### 阶段 4 · Agent 工具调用 + Skills

```markdown
阶段 4：让助理会动手。

A. 3 个工具（不多做）：
   - create_calendar_event（标题、开始时间、时长）
   - set_reminder（内容、触发时间）
   - read_clipboard（读取并总结）

B. 实现方式（硬约束第 10 条）：
   - system prompt 里给出 JSON schema，要求「需要调用工具时只输出 JSON，不输出其他文字」
   - 客户端解析 JSON → 参数校验 → 调 Android API 执行 → 结果回灌模型 → 生成自然语言回复
   - JSON 解析失败：让模型重试一次；仍失败则提示用户「我没听懂，可以换个说法」
   - 每个工具执行前展示确认条（用户点确认才执行），避免误操作

C. 极简 Skills（3 个 Markdown 文件，放 assets/skills/）：
   - daily-briefing.md：每日简报（读剪贴板 + 日历今日安排 → 汇总）
   - meeting-notes.md：会议纪要整理（粘贴文本 → 结构化输出）
   - file-organize.md：文件归档建议（扫描目录 → 给出归类建议，不自动移动）
   加载方式：识别到相关意图时，把对应 Markdown 内容注入 system prompt。
   不做 Skills 引擎、不做动态加载市场。

产出：3 个工具可真实执行 + 3 个 Skills 可触发。
验收：说「明天下午三点提醒我开会」能在系统日历创建成功；
      说「帮我整理今天的会议纪要」能走 meeting-notes 流程。
```

### 阶段 5 · 后端 + 共享契约

```markdown
阶段 5：补齐单仓的后端与共享层（对应招聘 JD 的「前后端同仓」）。

A. shared/（KMP）：
   ChatMessage、ModelInfo、DeviceProfile、PerfReport、ToolCall、SkillMeta
   用 kotlinx.serialization 定义，Android 与后端共用同一份契约。

B. backend/（FastAPI + SQLite）：
   - GET  /models            模型清单（名称/体积/量化/下载地址/校验和/最低内存）
   - GET  /models/recommend  入参 DeviceProfile，返回推荐的模型与参数
                             （规则与客户端保持一致，便于后续云端下发调整）
   - GET  /prompts/{id}      prompt 模板版本管理
   - POST /perf              接收性能埋点（机型/模型/5 项指标）
   - GET  /health

C. Android 端：
   - 启动拉模型清单 → 与本地版本比对 → 断点续传下载
   - 推荐结果优先用云端返回，失败回落本地规则
   - 性能数据批量上报（失败指数退避重试）

D. docs/05-api.md：接口文档 + 请求响应示例。

产出：后端可本地启动 + 客户端完整对接。
验收：App 能从后端拉到模型清单并下载；跑一轮对话后后端能查到性能记录。
```

### 阶段 6 · 作品化（决定面试印象）

```markdown
阶段 6：把项目变成 5 分钟能看懂的作品。

1. docs/vibe/：5 个真实 AI 协作案例
   格式：需求 → 我给的 prompt → AI 首版输出 → 问题 → 修正 prompt → 最终方案
   必须含 2 个失败案例（例如 AI 编造了不存在的 JNI 方法、GPU 参数给错）

2. README.md（中文为主）：
   - 首屏：一句话定位 + 演示 GIF/视频
   - 架构图（ui / domain / engine / capability / shared / backend）
   - 设备适配表：3 台不同配置设备上推荐的模型与实测跑分
   - 关键设计决策：为什么 1.5B、为什么 BM25 先行、为什么不用原生 function calling、
     为什么不做无障碍自动化
   - 与 MNN Chat / PocketPal 的差异说明（我做的是 Agent 产品，不是聊天壳）
   - 设备要求与降级策略

3. 输出博客大纲两篇：
   《MNN 端侧 1.5B 模型 Android 实测：prefill/decode 数据与调优》
   《一个人 + AI 四周做出单仓端侧 AI App》

验收：README 首屏能让人明白这是什么、跑多快、怎么跑起来。
```

---

## 第三部分 · 设备探测与模型推荐规格（新增核心）

### 3.1 需要采集的字段

| 分类 | 字段 | 数据来源 | 用途 |
|---|---|---|---|
| 芯片 | SoC 型号 | `Build.SOC_MODEL`（API 31+，低版本回退 `Build.HARDWARE`） | 判断 NPU 能力、性能档位 |
| 芯片 | 厂商/平台 | `Build.MANUFACTURER`、`Build.BOARD` | 骁龙/天玑/麒麟分流 |
| CPU | 核心数 | `Runtime.getRuntime().availableProcessors()` | 推荐线程数 |
| CPU | 最高主频 | 读 `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` | 辅助判断档位 |
| 内存 | 总内存 | `ActivityManager.MemoryInfo.totalMem` | **模型分级主依据** |
| 内存 | 可用内存 | `MemoryInfo.availMem` | 运行时降级判断 |
| 内存 | 单应用堆上限 | `ActivityManager.getMemoryClass()` / `Runtime.maxMemory()` | 避免 OOM |
| 存储 | 可用空间 | `StatFs` | 判断能否下载模型 |
| 系统 | Android 版本 | `Build.VERSION.SDK_INT` | 兼容性 |
| ABI | 支持的 ABI | `Build.SUPPORTED_ABIS` | 必须含 arm64-v8a |
| GPU | GLES 版本 | `ActivityManager.getDeviceConfigurationInfo().reqGlEsVersion` | 能否走 GPU |
| GPU | Vulkan 支持 | `PackageManager.hasSystemFeature(FEATURE_VULKAN_HARDWARE_VERSION)` | 加速路径 |
| NPU | 厂商加速可用性 | 按 SoC 型号匹配厂商列表（骁龙→QNN/Hexagon） | 进阶加速（可选） |

**实现要求**：每个字段独立 `runCatching`，失败给 null 或兜底值；不申请敏感权限；结果做本地缓存。

### 3.2 模型分级推荐规则（初版，可调）

| 总内存 | 推荐模型 | 量化 | 上下文 | 理由 |
|---|---|---|---|---|
| < 6GB | Qwen2.5-0.5B | W4 | 2K | 保证不崩，速度优先 |
| 6-8GB | Qwen2.5-1.5B | W4 | 4K | 体验与体积平衡点 |
| 8-12GB | Qwen2.5-3B | W4 | 4K | 质量优先 |
| ≥ 12GB | Qwen2.5-3B（可试 7B） | W4 | 4K | 7B 需实测，不稳则回落 3B |

### 3.3 参数推荐规则

| 参数 | 规则 |
|---|---|
| 线程数 | `min(CPU 核心数, 6)`，默认 4；小核多的机型压到 4 |
| GPU 加速 | 支持 Vulkan/OpenCL 且模型量化格式兼容 → 开启，否则 CPU |
| 上下文长度 | 按上表；运行时内存告警自动减半 |
| 温度 / top-p | 0.7 / 0.9（可在设置页覆盖） |
| 降级策略 | 加载失败 → 自动降一档模型；仍失败 → 提示用户手动选择 |

### 3.4 这个功能为什么值得做（写进 README 的设计说明）

- 公司有多款定制设备，配置跨度大，**一份 APK 必须自适应**
- 把「适配」从人工试错变成**规则化推荐 + 可解释**
- 规则表外置，后续可由后端下发调整，不用发版
- 面试价值：端侧适配能力是「能不能真的落地」的分水岭

---

## 第四部分 · 竞品功能对照（设计依据，可写进 README）

| 项目 | 它的功能 | 我们的取舍 |
|---|---|---|
| **PocketPal AI**（★7.2k，MIT） | GGUF 模型下载/切换、内置 benchmark + 社区排行榜、Pals 人格、参数调节、TTS、工具调用 | 学它的 benchmark 设计与模型管理；不做排行榜、不做 TTS |
| **MNN Chat**（阿里官方） | 多模态（文/图/音/文生图）、断点续传、benchmark | 学它的 native 集成与模型分发；不做多模态（时间不够） |
| **MobiRAG**（MIT，Kotlin） | PDF 解析 + 分块 + ONNX 嵌入 + FAISS PQ 压缩 + **向量与 TF-IDF 混合检索** + Qwen0.5B；只存元数据保隐私 | 学它的混合检索与隐私设计；不引入 FAISS 重型依赖 |
| **PokeClaw**（★398，Apache 2.0） | AccessibilityService 控 UI、Skills 用 Markdown 定义免重编译 | 学它的 Skills 设计（只做 3 个）；**不做**无障碍自动化 |
| **MobileClaw** | 29 个原生工具 + 21 个 Skills + constrained decoding | 只做 3 个工具；参考它的工具注册方式 |
| **opendroid** | 自主规划、60+ actions、4 层知识图谱、12 家 provider | 全部不做（超出 4 周范围） |

**关键结论**：热门项目几乎全走 llama.cpp / LiteRT + GGUF，**用 MNN 做端侧 LLM Agent 的开源项目稀缺**——这是本项目的差异化位。

---

## 第五部分 · 防 AI 幻觉（每次开工前贴一次）

```markdown
1. 涉及 MNN / Android 系统 API 的部分，先给出你检索到的源码位置（文件 + 函数名），
   检索不到就写「未确认，需人工核实」，禁止编造。
2. 不确定的地方单独列一节「待确认清单」，不要用看似合理的代码掩盖不确定性。
3. 每次改完先自查：能编译吗？资源释放了吗？异常路径处理了吗？再说完成。
```

---

## 第六部分 · 前置准备清单（人来做）

| 项目 | 说明 |
|---|---|
| 设备摸底 | 跑阶段 1 的设备信息页，把 2-3 台主力设备的芯片/内存/Android 版本/加速能力记下来 |
| 模型文件 | 提前在魔搭下载 Qwen2.5-0.5B/1.5B/3B 的 MNN 版本，确认体积与量化精度 |
| MNN 源码 | 本地 clone 一份，AI 才能检索真实 API |
| 个人仓库 | 建在个人 GitHub 账号下；公司设备只做兼容性与性能采集 |
| 合规边界 | 不带公司业务代码、内部接口、设备敏感信息进个人仓库 |
| 时间缓冲 | 阶段 3 的向量检索若卡住，直接交付 BM25 版本，不影响整体 |

---

## 第七部分 · 开工前最后检查（v2 追加）

### 7.1 三个技术止损点（最重要，先看这个）

| 风险 | 规避方式 | 止损线 |
|---|---|---|
| **自己编译 MNN** | **优先用官方预编译 .so**，不要从源码构建（编译通常要 1-2 天且易失败） | 源码构建超过 4 小时仍失败 → 立刻回到预编译方案 |
| **模型下载慢/失败** | 优先用**魔搭 ModelScope**（国内直连），HuggingFace 作为备选 | 下载超过 30 分钟未完成 → 换更小模型（0.5B 先跑通） |
| **阶段 1 跑不通对话** | 先确认 .so 能被加载（`System.loadLibrary` 不报错）→ 再确认模型路径 → 再确认 tokenizer | **阶段 1 卡满 5 天** → 降级为「仅对话 + 设备探测」的最小版本，Agent/RAG 挪到第二阶段迭代 |

### 7.2 模型文件放哪（Android 11+ 分区存储）

- 路径：`/sdcard/Android/data/<你的包名>/files/models/<model_name>/`
- 不要放 assets（会把 1GB 打进 APK）
- 下载时校验 SHA-256，校验失败要能重下
- `.gitignore` 必须排除：`*.mnn`、`*.bin`、`models/`、`*.gguf`（GitHub 单文件上限 100MB）

### 7.3 Git 与仓库纪律（决定"看起来像不像真项目"）

- **仓库先建私有**，验收完再决定是否公开；面试时给链接或截图即可
- 每个阶段至少 3-5 次提交，**不要一次性提交全部代码**（一眼假）
- commit message 用规范前缀：`feat:` / `fix:` / `refactor:` / `docs:` / `chore:`
- 在公司电脑上：**不要把个人 GitHub token 配到全局 git config**，用单独的 SSH key 或临时凭证，用完删除
- 公司设备上装的测试 App，测完卸载；不保留个人项目的用户数据

### 7.4 AI 工具规则文件放哪（不同工具认不同文件）

| 工具 | 规则文件位置 |
|---|---|
| Cursor | `.cursor/rules/project.mdc` |
| Claude Code | `CLAUDE.md`（项目根目录） |
| Codex / Copilot | `AGENTS.md`（根目录）+ `.github/copilot-instructions.md` |
| Trae | 项目根目录规则配置（在其设置里指定） |

**建议**：主规则写进 `AGENTS.md`（通用性最好），再用软链接或复制一份到各工具专属位置，避免重复维护。

### 7.5 提示词的使用技巧（比内容本身更影响结果）

1. **不要一次贴全部阶段卡**——AI 会混乱、跳步。一次一张，验收通过再下一张。
2. **每阶段结束让它自评**：追加一句"列出本阶段已完成 / 未完成 / 已知风险"，你能立刻发现它漏了什么。
3. **AI 反复改不对时**：不要继续让它打补丁，直接说"回滚到上一个可编译版本，重新给方案"，避免代码越改越烂。
4. **每次让它先说方案再写码**（主规则第 3 条），发现方案不对就打断，省下大量返工。
5. 遇到它编造 API：把「第五部分 · 防 AI 幻觉」那三句话重贴一次，通常会立刻收敛。

### 7.6 面试材料（项目做完前就要准备）

- **演示视频**：`adb shell screenrecord /sdcard/demo.mp4`，1-2 分钟，飞行模式开头
- **多设备跑分表**：公司几台不同配置的设备正好用上，一张表比说十句话有力
- **简历话术**（一句话 + 三个数字）：
  > 独立完成单仓端侧 AI 助理（Android + FastAPI + KMP），基于 MNN 部署 Qwen2.5-1.5B 量化模型，
  > 首 token 1.8s、decode 12 tok/s、内存峰值 1.2GB；实现设备能力探测与模型分级推荐，
  > 同一 APK 覆盖 5 款不同配置设备。

### 7.7 一个容易忽略的加分点

公司明确 4.5 天工作制、反对 996。README 或博客里可以自然体现「用 AI 工具提效，而不是堆工时」的工程态度——**这是文化匹配信号，不是技术信号**，但技术负责人会看。

