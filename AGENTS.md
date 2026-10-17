# AGENTS.md · LiteAgent

> 本文件是长期规则，所有 AI 协作（Cursor / Trae / Claude Code / Codex）都以它为准。
> 最后更新：2026-10-17（依据 MNN `dbf9ba6` 实际源码核对后修订）

## 1. 这个项目是什么

一个**完全离线的手机 AI 助理**：模型在本地、推理在本地、文档不出设备。
能对话、能读手机里的文档回答问题并标注来源、能建日程设提醒。

三条产品原则（决定优先级，不可动摇）：

1. **本地 RAG 是主功能，聊天是入口。** 端侧小模型的聊天质量打不过云端，唯一不可替代的是"文档不上传"。
2. **一份 APK 自适应多机型。** 亿级用户规模下机型极度分散，设备探测 + 模型分级推荐是核心能力，不是装饰。
3. **端侧推理的商业价值是边际成本归零。** 对用户是"买断不按次收费"，对公司是"亿级规模下 API 账单归零"。README 两层都要写。

## 2. 技术栈（已锁定）

| 层 | 选型 |
|---|---|
| 客户端 | Kotlin 2.0.21 + Jetpack Compose，minSdk 26，targetSdk 35，仅 arm64-v8a |
| 推理 | MNN（LLM 引擎），JNI 封装，不自研推理内核 |
| 后端 | Python 3.11 + FastAPI + SQLite |
| 共享层 | Kotlin Multiplatform（androidTarget + jvm，**不做 iOS**） |
| 构建 | Gradle 8.13 + AGP 8.7.3 + Kotlin DSL，单仓 |

## 3. 硬约束（违反即失败）

1. **涉及 MNN 的代码必须先查本地源码再写**，参考副本在 `.refs/MNN`（不入库）。查不到就写「未确认」，禁止编造 API。
2. **已核实的 MNN 事实**（照此写，不要凭记忆改）：
   - LLM 头文件：`transformers/llm/engine/include/llm/llm.hpp`，命名空间 `MNN::Transformer`
   - 创建：`Llm::createLLM(configPath)` → `set_config(json)` → `load()` → `destroy(llm)`
   - 推理：`Llm::response(content, std::ostream*, end_with, max_new_tokens)`，**流式靠 ostream，没有回调 API**
   - 中断：`Llm::stoped()`；状态枚举 `LlmStatus`
   - 性能：`LlmContext` 自带 `load_us / prefill_us / decode_us / sample_us / ttfa_us`
   - GPU：**OpenCL**（`-DMNN_OPENCL=ON`），不是 Vulkan
   - 编译：`project/android/build_64.sh`，产物 `project/android/build_64/lib/libMNN.so`
3. 不要复制 MnnLlmChat 的 UI 层与业务层代码，只参考 native 集成方式。
4. **单次改动不超过 3 个文件**，禁止大爆炸式重构。
5. 不允许留 TODO / NotImplementedError 后宣称完成；编译不过不算完成。
6. 所有推理相关代码必须打点：加载、prefill、首 token、decode、内存峰值，统一走 `PerfLogger`。
7. 模型不进 APK，首次启动下载或本地导入，必须断点续传 + SHA-256 校验。
8. 引入新依赖前先说明理由与体积代价。
9. 设备能力必须优雅降级：探测失败给兜底值，**并且把失败字段记进 `DeviceProfile.failures`**，不许静默吞异常。
10. 工具调用不依赖模型原生 function calling，统一走「JSON schema 约束输出 + 客户端解析 + 失败重试一次」。

## 4. 代码规范

- 分层：`ui / domain / data / engine / capability`，engine 与 capability **不依赖 UI**
- 协程 + Flow 做流式，禁止在 JNI 回调线程直接更新 UI
- JNI 资源必须有明确 acquire/release 生命周期
- 关键路径写中文注释，解释**为什么**，不是做了什么
- 规则表（设备分级、模型推荐）外置为 `recommend-rules.json`，**客户端与后端共用同一份文件**，禁止两端各写一套 if-else

## 5. 已知坑（踩过或核对过）

| 坑 | 表现 | 对策 |
|---|---|---|
| `totalMem` 比标称小 0.4–0.8GB | 6GB 机落到 0.5B 档，`>=12GB` 档永不命中 | 先按 `{4,6,8,12,16}` 归并到标称档位再查表 |
| `onProgress` 返回值语义反直觉 | 返回 true 表示"停止" | 我们的 JNI 改名为 `onToken(): Boolean`，注释写清 |
| UTF-8 多字节被 token 边界切断 | 中文流式出现乱码方块 | 必须过 `utf8_stream_processor` 同类逻辑 |
| 1.5B 长文本生成 30–60s | 屏幕黑掉进程被挂起 | 前台服务 `foregroundServiceType="dataSync"` |
| 16KB page size | Android 15 上装不上 | `-Wl,-z,max-page-size=16384` |

## 6. 输出格式

每次回复：方案（3–5 行）→ 变更文件清单 → 完整代码 → 自检结果 → 下一步建议。
先说方案再写码，方案不对就打断。
