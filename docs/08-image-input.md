# 08 · 图像输入：从 JD 到实现的取舍

> 时间：2026-10-17 21:05
> 设备：DNAKE v911（无 Google Play Services）
> 结论：**图片 → 文字 → 模型回答，整条链路在无 GMS 的 2GB 设备上跑通了。**

---

## 一、JD 里到底写了什么

我在鱼泡直聘上找到了这份岗位的完整原文。**没有"图像生成"四个字**，但图像相关的东西在三处：

**1. 职责第 2 条（最硬）**
> 负责端侧大模型、AI 智能体、推理引擎的落地与性能优化，包括 **MNN-Chat 等端侧方案的适配、调优与规模化落地**

MNN-Chat 官方支持"多模态（文/图/音/文生图）"。要"适配"MNN-Chat，图像输入是绕不过去的。

**2. Bonus 第 2 条**
> 有海外应用、工具类、AI 类、**图形类产品研发经验**

这是履历项。

**3. 同公司的另一个在招岗位**
> AI-Python-AIGC 研发工程师（ComfyUI 方向）：图像生成、编辑、视频生成；Diffusion / LoRA / ControlNet

说明这家公司**确实有图像生成产品线**，只是走 Python + ComfyUI，不走 Android。

所以图像能力是真实需求，但落在 Android 岗上，最合理的切入点是**图像作为输入**，而不是图像生成。

## 二、为什么是 OCR，不是视觉大模型

技术上有三条路：

| 路线 | 模型体积 | 这台 2GB 设备 | 能回答什么 |
|---|---|---|---|
| 视觉大模型（Qwen2-VL 类） | 1.5GB+ 权重 | ❌ 0.5B 已吃掉 688MB | "图里是只猫吗" |
| 图像生成（SD 量化） | 1GB+ | ❌ 且极慢 | 画图 |
| **OCR（ML Kit）** | **~14MB** | ✅ 实测可用 | **"这张文档说了什么"** |

选了第三条。

理由不是"做不到前两条"，而是**前两条在这台设备上做出来也不能用**。而 OCR 这条路：

- 立刻能跑通，能在真机验证
- 和 RAG 天然衔接：拍一张文档 → OCR → 问内容 → 检索 + 引用
- 对"工具类 AI 应用"来说，拍文档问答比"识别猫"实用得多

## 三、实现

```
用户点图片按钮 → SAF 选图 → ML Kit 中文 OCR → 文字回填输入框 → 用户确认 → 发送
```

**为什么回填输入框而不是直接发送**：OCR 会认错字（尤其手写、表格、竖排），让用户看一眼再发，比替他去问靠谱。

**截断 2000 字**：一张 A4 满页中文约 1500 字，再多会把上下文吃光（1K 上下文的极限档根本放不下）。

## 四、实测结果

### 识别质量

原图（PIL 渲染的四行中文）→ 识别结果：

| 原图 | 识别 |
|---|---|
| 量子堆栈 AI-Native 工程师 | 量子堆栈Al-Native 工程师 |
| 端侧大模型 推理引擎 | 端侧大模型推理引擎 |
| 量化精度 W4 模型体积 531MB | 量化精度 W4模型体积 531MB |
| decode 8.3 tok/s | decode 8.3 tok/s |

- ✅ 四行全部识别成功
- ⚠️ `AI` 的大写 I 被认成小写 `l`（"Al-Native"）
- ⚠️ 行内空格被吃掉

对"文档问答"这个用途，这个准确率够用——错的是字母形近字，不是语义。

### 最关键的一条：**没有 Google 服务也能用**

这台设备 `pm list packages | grep google` 是空的，完全没装 GMS。实测日志：

```
W GooglePlayServicesUtil: Google Play services is missing.
D DecoupledTextDelegate: Start loading thick OCR module.
I DynamiteModule: Considering local module com.google.mlkit.dynamite.text.chinese:10000
                    and remote module com.google.mlkit.dynamite.text.chinese:0
I DynamiteModule: Selected local version of com.google.mlkit.dynamite.text.chinese
D nativeloader: Load .../libmlkit_google_ocr_pipeline.so ... ok
```

**`Selected local version`** —— ML Kit 的 DynamiteModule 发现远程模块版本是 0（没有 GMS 提供），于是回落到 APK 里打包的本地模块（版本 10000）。OCR 原生库从 APK 里直接加载成功。

这对国内设备是关键结论：**`com.google.mlkit:text-recognition-chinese` 是 bundled 版，不依赖 Google Play Services。**

### 完整链路（图 → 文字 → 提问 → 回答）

```
首 token 15764 ms · prefill 23.6 tok/s · decode 7.3 tok/s · 加载 4475 ms
```

**输入**（OCR 结果 + 用户问题，客户端拼装）：

```
图片里的文字：
量子堆栈Al-Native 工程师
端侧大模型推理引擎
量化精度 W4模型体积 531MB
decode 8.3 tok/s

这张图讲了什么
```

**输出**：

```
LiteAgent技术方案的推理框架是MNN。推理模型是Qwen2.5-0.5B，量化精度为W4，
文件大小是531MB。Qwen2.5-0.5B的文件大小是839MB，而Qwen2.5-0.7B的文件大小是2260MB。
LiteAgent技术方案的性能目标是目标设备的 decode 速度不低于5 tok/s……
```

链路通了，但暴露两个问题——都记在下面。

### 两个真实问题

### 1. 问题被丢弃（已修）

第一版跑出来是这样的：

```
问题的答案是：
[答案]
这是一个运行在手机本地的 AI 助理...
```

原因：autotest 的拼接逻辑写错了

```kotlin
val prompt = vm.state.value.draft.ifBlank { autoPrompt }   // draft 非空时把问题丢了
```

发给模型的只有一段 OCR 文本，**没有任何指令**。模型收到一堆没有问题的文字，开始复述 prompt 结构、输出占位符 `[答案]`、最后跑偏到"量子计算"。

修法：问题必须拼在 OCR 文本后面。

```kotlin
val prompt = if (ocrText.isBlank()) autoPrompt else "$ocrText\n\n$autoPrompt"
```

这也是真实用户会踩的坑：**如果输入框里只有 OCR 文字就直接点发送，模型同样会迷失**。UI 上应该引导用户"接着输入你的问题"，而不是允许直接发一段素材。

### 2. RAG 会污染图片问答

修好拼接之后，回答变正常了，但内容混进了**文档库里的片段**：

> Qwen2.5-0.5B 的文件大小是 839MB，而 **Qwen2.5-0.7B** 的文件大小是 2260MB

- "LiteAgent技术方案"是之前 RAG 测试注入的文档，还在库里
- 用户问"这张图讲了什么"，BM25 命中了文档里的"量化精度""decode"等词
- 于是文档片段和图片文字一起进了 prompt
- 模型把两者混着说，还编出了 0.7B / 0.9B / 1.1B 这些不存在的型号

**根因是产品设计问题**：图片问答时，用户的意图是"读这张图"，不是"查我的文档库"。这时应该是两条独立链路，不该混。

**没修，因为这是设计决策**。可选方向有三个：

| 方案 | 做法 | 代价 |
|---|---|---|
| A. 图片问答时禁用检索 | 检测到带图输入就不走 RAG | 简单，但用户想问"这张图和我文档里的方案对得上吗"就没法做 |
| B. prompt 里明确分区 | `【图片内容】` / `【本地文档】` / `【问题】` 三段分隔 | 小模型可能还是分不清 |
| C. 让用户选 | 输入框上方给个"参考我的文档"开关 | 多一个交互步骤 |

我倾向 B + C 组合：默认分区，用户可显式开启/关闭检索。但这需要再做一轮真机验证，这轮没做。

### 疑点：prefill 耗时

首 token 从平日的 230ms 涨到 **15764ms**。

按 prefill 23.6 tok/s 倒推，prompt 约 370 token——OCR 文本 + RAG 片段 + system prompt，量级对得上。但对用户来说，**拍一张图要等 15 秒才出第一个字**，这个体验不可接受。

真机上更合理的做法是：OCR 完成后立刻给用户看识别结果（已经做了），生成慢的话让用户自己决定要不要等。

### 体积代价

| | APK |
|---|---|
| 加 OCR 前 | 8.4 MB |
| 加 OCR 后 | **22 MB** |

+13.6MB。对"工具类产品"是个不小的涨幅（甜气AI 是 8–12MB 量级）。取舍是：用 13.6MB 换"拍文档问答"，值。

## 五、离真正的多模态还有多远

MNN 本身是支持图像输入的，我确认了接口：

```cpp
struct PromptImagePart {
    MNN::Express::VARP image_data;
    int width;
    int height;
};
void response(const MultimodalPrompt&, ...);   // llm.hpp:211
```

`is_visual: true` 的模型会走 `Omni` 分支（llm.cpp:93），吃 `MultimodalPrompt` 里的 images/audios/videos。

**要接这条路，还缺三样**：

1. 一个能在目标设备跑的视觉模型（Qwen2-VL 最小 2B，这台机器放不下）
2. 图像预处理（resize 到模型要求尺寸、归一化、转 NCHW float）
3. JNI 改造（传像素数组、构造 VARP、拼 `prompt_template` 占位符）

代码路径清楚，但**没有模型就没法验证**。所以这版先走 OCR，把"图像作为输入"这件事做成能用的样子；多模态留给有 8GB 设备的场景。

## 六、局限（诚实清单）

1. **只认字，不认画面**。问"这是一张什么照片"它答不出来，只能读到图里的文字。
2. **没有做图像生成**。公司那边的图像生成走 ComfyUI + Diffusion，与端侧 2GB 设备无关。
3. **表格和竖排没验证**。这轮只测了横排、白底黑字。
4. **SAF 选图这一步没自动化**。测试用的是 `--es autoocr <path>` 直接喂文件路径；真实用户走的是系统文件选择器，需要手动点一次。
5. **没有做图像缓存**。同一张图重新选一次会重新 OCR。

## 七、怎么复现

```bash
# 1. 把一张含中文的图放到设备私有目录
adb push test.png /data/local/tmp/test.png
adb shell "cp /data/local/tmp/test.png /data/data/com.liteagent.app/files/test.png"

# 2. 只跑 OCR（结果回填输入框）
adb shell am start -n com.liteagent.app/com.liteagent.app.MainActivity \
  --es autoocr /data/data/com.liteagent.app/files/test.png

# 3. 跑完整链路（OCR → 提问 → 模型回答）
adb shell am start -n com.liteagent.app/com.liteagent.app.MainActivity \
  --es autoocr /data/data/com.liteagent.app/files/test.png \
  --es autotest "这张图讲了什么"
```
