# 07 · 端侧推理框架技术预研

> 时间：2026-10-17
> 范围：MNN / llama.cpp / ExecuTorch / LiteRT / ONNX Runtime Mobile
> 方法：公开资料 + 本项目在真实低端设备上的实测
> 局限：只有 MNN 是我亲手编译、亲手跑过数据的，其余四个是二手资料，下面的对比表里明确标了来源。

---

## 一、五个框架到底是什么

| 框架 | 出身 | 定位一句话 |
|---|---|---|
| **MNN** | 阿里 | 中文移动端生产首选，对国产 SoC（骁龙 Hexagon / 麒麟 DaVinci / 联发科 APU）适配深 |
| **llama.cpp** | 社区（ggml） | 端侧 LLM 事实标准，GGUF 生态，跨平台零依赖，CPU 推理效率最高 |
| **ExecuTorch** | Meta | PyTorch 官方端侧方案，AOT 编译，加载几乎零延迟，Snapdragon NPU 路径最快 |
| **LiteRT / LiteRT-LM** | Google | TensorFlow 系的官配，delegate 体系最成熟，但 LLM 支持节奏慢 |
| **ONNX Runtime Mobile** | 微软 | 跨框架跨平台一致性最好，代价是移动 GPU 加速不如原生方案 |

关键区别不在"谁快"，在**谁提供生成式推理循环**：

> 传统模型看算子覆盖和 delegate；大模型必须看"是否提供生成循环 + KV cache 管理"。
> LiteRT GenAI / ORT GenAI / MNN-LLM 是"直接能做产品"的；通用 runtime 得自己搭。

## 二、公开 benchmark（注意设备）

来自 AlephZero Labs 的对比，**Snapdragon 8 Elite，Llama 3.2 1B，INT4**：

| 框架 / 后端 | decode (tok/s) | 首 token (ms) | runtime 体积 |
|---|---|---|---|
| ExecuTorch + QNN (NPU) | 18–25 | 400–600 | ~8 MB |
| MNN + NNAPI (NPU) | 10–18 | 350–550 | ~6 MB |
| llama.cpp + Vulkan (GPU) | 8–14 | 300–500 | ~4 MB |
| MNN (CPU) | 5–9 | 200–400 | ~6 MB |
| ExecuTorch + XNNPACK (CPU) | 6–10 | 200–350 | ~8 MB |
| llama.cpp (CPU NEON) | 4–8 | 150–300 | ~4 MB |

另一组来自 xckevin 的实测，**Snapdragon 8 Gen 3，Gemma 2B**：

| 框架 | decode | 备注 |
|---|---|---|
| llama.cpp | 1.8 s/token（≈0.56 t/s） | 比 ExecuTorch 快约 20% |
| ExecuTorch | 2.3 s/token（≈0.43 t/s） | AOT 编译，加载几乎零延迟 |

两组数据的绝对数字差了两个数量级，因为**模型、量化、上下文、设备全不同**。这恰好说明一件事：

**benchmark 只有在自己目标设备上跑才有意义。** 这两组数据我用来看"相对排序"，不用来看"绝对性能"。

## 三、本项目的实测（唯一一手数据）

一台国产安卓工控设备 / Allwinner A537 / **1950MB** / Android 15 / Qwen2.5-0.5B W4 / 1K 上下文：

| 指标 | 值 |
|---|---|
| decode | **8.3 tok/s**（3 线程，4 组实验最优） |
| prefill | 4.4 → 15.1 tok/s（随输入长度上升） |
| 首 token | 230 / 656 / 1347 ms |
| 加载 | 6.5 s |
| 内存峰值 | 688 MB / 可用 692 MB |
| `libMNN.so` | **7.2 MB**（自编译，59 个 LLM 符号） |

**不要拿这个和人家的旗舰数据比**。我这台是 1.75GHz 的 A55 小核 + 2GB 内存，跑的是 0.5B + 1K 上下文；人家是 8 Elite + 1B。我们的 8.3 t/s 看着比"MNN CPU 5–9 t/s"还高，纯粹因为模型小一半、上下文短。

**唯一可比的结论**：同一份 MNN CPU 后端，在低端设备上跑小模型能到"可用"级别（8 tok/s = 一个汉字 120ms），不需要 NPU。

## 四、为什么最终选 MNN

三条理由，按权重排序：

**1. 中文小模型的官方量化版本现成（权重最高）**
MNN 团队在 ModelScope 维护 Qwen 系列的预转换权重，`config.json` 直接能用。llama.cpp 的 GGUF 中文模型多为社区转换，质量和上下文配置参差。对一个"要在中文场景半个月出可用产品"的项目，这是决定性的。

**2. 内置性能计数器**
`LlmContext` 直接给 `prefill_us / decode_us / ttfa_us`。虽然我发现纯文本路径下后两个不填（只有 `omni.cpp` 填），但至少 prefill 是现成的，其余自己补几行 `steady_clock` 就有。llama.cpp 得自己在 decode 循环里插桩。

**3. 做端侧 LLM Agent 的开源项目里，MNN 是空白位**
不是技术理由，但对一个要公开展示的项目有意义。

### 代价（必须说清楚）

**必须自己编译。** MNN 主仓库不发布 Android 预编译 `.so`（`apps/Android/MnnLlmChat/app/libs/` 是空的），官方 `build_64.sh` 还是给 unix `make` 写的。

实测成本：Windows + NDK 28 + Ninja，改 4 个开关后 **3 分 39 秒**出 7.2MB 的 `libMNN.so`。比预想低一个数量级，所以这个代价可以接受——但如果团队没有 Android NDK 经验，这会是一天的坑。

## 五、如果换一个场景，会怎么选

选型不是"哪个最好"，是"哪个匹配约束"。

| 场景 | 选什么 | 为什么 |
|---|---|---|
| 中文 App，要快出可用版本 | **MNN** | 中文量化模型现成 + 国内 SoC NPU 适配深 |
| 纯 CPU 推理，追求极限速度 | llama.cpp | CPU 效率目前无对手，LLM 架构集成成本最低 |
| 模型来自 PyTorch，目标 Snapdragon | ExecuTorch | QNN delegate 的 NPU 路径最快，AOT 加载零延迟 |
| 已有 TensorFlow/ONNX 栈，跑传统模型 | LiteRT / ORT | delegate/EP 体系成熟，别换栈 |
| 要 Android + iOS + Web 一套模型 | ONNX Runtime | 一致性最好，代价是移动 GPU 加速一般 |
| 只做 iOS | CoreML | Neural Engine 能效比最高，锁生态也认了 |

有一条经验值得单独记：

> **NPU 加速应该作为可选优化，不要作为硬依赖。**

理由：NPU 路径的算子覆盖率、驱动稳定性、机型碎片化都比 CPU 差一个量级。产品初期用 CPU 跑通、把 NPU 当加速开关，比一开始就绑死 NPU 稳得多。这个项目从一开始就关掉 GPU（这台设备的 Mali-G57 OpenCL 驱动不可靠），也是同一个判断。

## 六、2026 年的三个趋势判断

**1. 框架之争已经从"能不能跑"变成"KV cache 管得好不好"**
通用 runtime（LiteRT、ORT）在补 GenAI 专用栈，说明"生成循环 + cache 管理"成了分水岭。谁的 KV cache 内存布局更省、增量推理更快，谁赢。

**2. NPU 是性能上限，CPU 是产品下限**
旗舰机上 NPU 能把 decode 拉到 18–25 t/s，但低端机上没有 NPU 可用，只能靠 CPU。一个成熟的产品必须两条路都有，且能在运行时降级。

**3. 模型侧的优化比框架侧更有效**
这个项目的两次误判都在这里：0.5B 能跑是因为 `DiskEmbedding`（272MB 词嵌入走磁盘），1.5B 能跑是因为 `tie_embeddings` + mmap。**同一框架下，模型怎么切分比框架怎么调重要得多。**

## 七、这份预研的诚实边界

- 只有 MNN 是一手实测，其余四个的结论来自公开资料，未在本项目验证
- 公开 benchmark 的设备是旗舰机，与目标设备（2GB 低端）差两个档位，只能看相对排序
- 没有做"同设备、同模型、换框架"的对照实验——那需要把四个框架都编一遍，成本远超这个项目的范围
- 如果要真做这个对照，最小可行方案是：固定 0.5B + 同一台设备，只测 llama.cpp / MNN 两个（GGUF 和 MNN 格式转换都可控），其余三个的 NPU 路径在低端机上大概率跑不起来
