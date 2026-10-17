# 00 · MNN 端侧 LLM 集成调研

> 核对时间：2026-10-17
> MNN 版本：commit `dbf9ba6`（2026-10-15，`[CPU:Bugfix] Preserve RVV INT8 sum accumulator tails`）
> 本地路径：`.refs/MNN`（sparse checkout，仅拉取 `apps/Android/MnnLlmChat`、`include`、`transformers/llm/engine/include`、`project/android`）
> 核对环境：Windows / NDK `28.0.12433566` / CMake `3.30.5` / JDK 17 / Android SDK platform 35

---

## 1. 结论先行（四条，都推翻或修正了 v2 方案的假设）

| # | v2 方案的假设 | 核对结果 | 影响 |
|---|---|---|---|
| 1 | 「优先用官方预编译 .so」 | **MNN 主仓库不发布 Android 预编译库**。`apps/Android/MnnLlmChat/app/libs`、`src/main/jniLibs` 均为空；`build_prebuilt.gradle` 只负责下载**模型**，不是 .so | 必须自编译，止损线要重新定 |
| 2 | LLM 头文件在 `include/MNN/expr/Transformer.hpp` | **该文件不存在**。真实路径 `transformers/llm/engine/include/llm/llm.hpp` | 照 v2 写代码会直接 include 失败 |
| 3 | GPU 加速「支持 Vulkan/OpenCL」 | MNN 官方 Android 编译脚本只开 `-DMNN_OPENCL=ON`，**没有 Vulkan 开关** | 设备探测里的 Vulkan 判断与 MNN 实际能力不对应 |
| 4 | 5 项性能指标需要自己打点 | MNN 的 `LlmContext` 原生提供 `load_us / prefill_us / decode_us / sample_us / ttfa_us` | Benchmark 阶段工作量下降一个量级 |

---

## 2. 真实 API 契约

全部来自 `transformers/llm/engine/include/llm/llm.hpp`（下文行号对应此文件）。

### 2.1 生命周期

| 动作 | 签名 | 行号 |
|---|---|---|
| 创建 | `static Llm* Llm::createLLM(const std::string& config_path)` | 161 |
| 销毁 | `static void Llm::destroy(Llm* llm)` | 162 |
| 加载 | `virtual bool Llm::load()` | 165 |
| 配置 | `bool Llm::set_config(const std::string& content)` | 199 |
| 读配置 | `std::string Llm::dump_config()` | 198 |
| 重置 | `void Llm::reset()` | 172 |

注意：`createLLM` 收的是 **config 路径**（模型目录里的 `config.json`），不是模型文件名。传错会返回 `nullptr`。

### 2.2 推理

| 形态 | 签名 | 行号 |
|---|---|---|
| 纯文本 | `void response(const std::string& user_content, std::ostream* os = &std::cout, const char* end_with = nullptr, int max_new_tokens = -1)` | 183 |
| 多轮 | `void response(const ChatMessages& chat_prompts, ...)` | 184 |
| 多模态 | `void response(const MultimodalPrompt&, ...)` | 211 |

`ChatMessage` 的定义（第 39 行）：

```cpp
using ChatMessage  = std::pair<std::string, std::string>;  // first = role, second = content
using ChatMessages = std::vector<ChatMessage>;
```

**流式输出走 `std::ostream*`**，不是回调函数。这是 MNN 的核心设计，也是初学最容易踩空的地方——第一次接的时候会下意识找 `setCallback`，但 MNN 没有这个东西。

### 2.3 状态与中断

```cpp
enum class LlmStatus {          // llm.hpp:107
    NOT_LOADED = -1, RUNNING = 0, NORMAL_FINISHED = 1,
    MAX_TOKENS_FINISHED = 2, USER_CANCEL = 3, INTERNAL_ERROR = 4, TIMEOUT = 5
};
bool Llm::stoped();             // llm.hpp:190
```

`stoped()`（MNN 自己的拼写）是中断入口。上层通过 ostream 写入失败让解码循环退出——具体机制见 `apps/Android/MnnLlmChat/app/src/main/cpp/llm_stream_buffer.hpp`，MnnLlmChat 用一个自定义 streambuf 接管输出并回调 Java。**该文件的完整中断语义待独立验证**（见第 6 节）。

### 2.4 性能数据（重点）

```cpp
struct LlmContext {             // llm.hpp:119
    int prompt_len, gen_seq_len, all_seq_len;
    int64_t load_us, vision_us, audio_us;
    int64_t prefill_us, decode_us, sample_us, ttfa_us;
    LlmStatus status;
    std::string generate_str;
    ...
};
const LlmContext* Llm::getContext() const;   // llm.hpp:215
```

对应到方案要的 5 项指标：

| 指标 | 取值方式 |
|---|---|
| 模型加载耗时 | `context->load_us` |
| prefill 耗时 | `context->prefill_us` |
| 首 token 延迟 | `context->ttfa_us` |
| decode 速度 | `context->gen_seq_len / (context->decode_us / 1e6)` tok/s |
| 内存峰值 | MNN 不提供，需自己读 `/proc/self/status` 的 `VmHWM` |

最后一项是唯一需要自采的指标，也是唯一能反映"会不会被 LMK 杀掉"的硬数据。

### 2.5 Embedding（阶段 3 第二步可直接用）

```cpp
class MNN_PUBLIC Embedding : public Llm {       // llm.hpp:297
    static Embedding* createEmbedding(const std::string& config_path, bool load = true);
    Express::VARP txt_embedding(const std::string& txt);
    static float cos_sim(VARP var0, VARP var1);
    int dim() const;
};
```

MNN 自带 embedding 推理和余弦相似度。**不需要引入 ONNX Runtime**，v2 方案里「引入轻量 embedding 模型（MNN/ONNX 版本）」的 MNN 分支就是这条。

---

## 3. JNI 层的真实形态

MnnLlmChat 的 JNI 在 `apps/Android/MnnLlmChat/app/src/main/cpp/llm_mnn_jni.cpp`，其 Java 侧类名为 `com.alibaba.mnnllm.android.llm.LlmSession`，导出符号：

```
Java_com_alibaba_mnnllm_android_llm_LlmSession_initNative
Java_com_alibaba_mnnllm_android_llm_LlmSession_submitNative
Java_com_alibaba_mnnllm_android_llm_LlmSession_submitFullHistoryNative
Java_com_alibaba_mnnllm_android_llm_LlmSession_releaseNative
Java_com_alibaba_mnnllm_android_llm_LlmSession_resetNative
Java_com_alibaba_mnnllm_android_llm_LlmSession_updateConfigNative
Java_com_alibaba_mnnllm_android_llm_LlmSession_runBenchmarkNative
```

回调方向（`llm_mnn_jni.cpp:164-181`）：

```cpp
jmethodID onProgressMethod = env->GetMethodID(progressListenerClass, "onProgress", "(Ljava/lang/String;)Z");
...
jboolean user_stop_requested = env->CallBooleanMethod(progressListener, onProgressMethod, javaString);
return (bool) user_stop_requested;
```

**语义陷阱**：Java 侧 `onProgress` 返回 `true` 表示「我要停」，不是「继续」。命名和直觉相反，第一次接必踩。我们在自己的 JNI 里会改成 `onToken(String): Boolean` 并明确注释「返回 true = 请求停止」。

另一个细节：`env->NewStringUTF` 在回调里每 token 调一次，且 UTF-8 多字节字符可能被 token 边界切断。MnnLlmChat 用 `utf8_stream_processor.hpp` 处理。中文场景这是**必须解决**的问题，否则会出现乱码方块——这也是我们能写出差异化说明的地方。

---

## 4. 集成路径对比与决策

| 路径 | 具体做法 | 可行性 | 结论 |
|---|---|---|---|
| A · 官方预编译 .so | — | **不存在** | 排除 |
| B · 抽 MnnLlmChat Release APK 里的 .so | 解包拿 `libMNN.so` + 头文件 | 可行但 JNI 符号固定为 `com.alibaba.mnnllm.*`，要么改自己的包名去迁就，要么重写动态注册；且版本随 APK 走，不可控 | 排除 |
| C · 自编译 libMNN.so | `project/android/build_64.sh` | `build_prebuilt.gradle` 证明仓库不自带二进制，官方 CI 也是这个脚本 | **采用** |

### 4.1 编译命令（真实脚本内容）

`project/android/build_64.sh`：

```bash
cmake ../../../ \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_ABI="arm64-v8a" \
  -DANDROID_STL=c++_static \
  -DMNN_USE_LOGCAT=false \
  -DMNN_BUILD_BENCHMARK=ON \
  -DMNN_USE_SSE=OFF \
  -DMNN_BUILD_TEST=ON \
  -DANDROID_NATIVE_API_LEVEL=android-21 \
  -DMNN_BUILD_FOR_ANDROID_COMMAND=true \
  -DMNN_LOW_MEMORY=ON \
  -DMNN_BUILD_DIFFUSION=ON \
  -DMNN_BUILD_OPENCV=ON \
  -DMNN_IMGCODECS=ON \
  -DMNN_OPENCL=ON \
  -DMNN_SEP_BUILD=OFF \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
  -DNATIVE_LIBRARY_OUTPUT=. -DNATIVE_INCLUDE_OUTPUT=. $*
make -j4
```

### 4.2 我们要改的三个开关

| 原值 | 改成 | 理由 |
|---|---|---|
| `-DMNN_BUILD_DIFFUSION=ON` | `OFF` | 不做图像生成，编进去纯增体积 |
| `-DMNN_BUILD_OPENCV=ON` / `-DMNN_IMGCODECS=ON` | `OFF` | 同上 |
| `-DMNN_BUILD_TEST=ON` | `OFF` | 减编译时间 |

保留 `-DMNN_LOW_MEMORY=ON`（手机端必开）、`-DMNN_OPENCL=ON`（GPU 路径）、`-DMNN_SUPPORT_TRANSFORMER_FUSE=ON`（LLM 必须）。

### 4.3 产物与链接方式

MnnLlmChat 的 `app/src/main/cpp/CMakeLists.txt` 给出真实链接方式：

```cmake
set(MNN_INSTALL_ROOT "${MNN_SOURCE_ROOT}/project/android/build_64")
set(LIB_PATH "${MNN_INSTALL_ROOT}/lib")
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${LIB_PATH}/libMNN.so")
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
```

两条可复用信息：

1. `-DMNN_SEP_BUILD=OFF` + `-DMNN_BUILD_FOR_ANDROID_COMMAND=true` 的产物是**单个 `libMNN.so`**，不是 `libMNN_Express.so` / `libMNN_CL.so` 一堆。
2. `-Wl,-z,max-page-size=16384` 是 **Android 15+ 的 16KB page size 要求**。不写这条，APK 在 Android 15 上装不上或起不来。这是面试里很好用的一细节——大多数作品集不会提。

---

## 5. 模型文件形态（来自 CMake 与实际代码，未含下载地址）

- 模型目录内必须有 `config.json`（`createLLM` 的入参指向它）和 `.mnn` 权重文件。
- MnnLlmChat 的下载校验逻辑以 `llm.mnn` 作为「模型已就绪」的判定文件（`build_prebuilt.gradle:52`）。
- `LlmSession::Load()` 里 mmap 的用法（`llm_session.cpp:203-218`）：

```cpp
bool use_mmap = !extra_config_["mmap_dir"].get<std::string>().empty();
llm_ = Llm::createLLM(model_path_);
config["use_mmap"] = use_mmap;
if (use_mmap) config["tmp_path"] = root_cache_dir_str;
llm_->set_config(config.dump());
model_loaded_ = llm_->load();
```

手机端 `use_mmap=true` 能显著降低峰值内存（权重按页调页，不整块进 RAM），代价是首次加载变慢、需要一块可写的 `tmp_path`。**我们的 DeviceProfiler 应该根据可用存储和内存决定是否开 mmap**——这正是「设备自适应」里最实际的一条规则，比调线程数有用得多。

---

## 6. 未确认清单（不猜，标注待验证）

| # | 事项 | 为什么重要 | 验证方式 |
|---|---|---|---|
| U1 | 自编译实际耗时与 `libMNN.so` 体积 | 决定 4 周一版能否成立 | 实跑一次 `build_64.sh`，记录时间和产物大小 |
| U2 | `llm_stream_buffer.hpp` 的完整中断语义 | 停止按钮能否真的立即停 | 读该文件 + 真机验证「生成中点停止」的响应延迟 |
| U3 | `utf8_stream_processor.hpp` 的切分策略 | 中文流式是否乱码 | 读文件 + 中文长文本实测 |
| U4 | OpenCL backend 在 Adreno/Mali 上的实际加速比 | 决定 GPU 开关是否有意义 | 真机对比 CPU 4 线程 vs OpenCL |
| U5 | Qwen 系列 MNN 量化模型的真实下载源与体积 | 模型管理模块的清单数据 | 查 ModelScope / HuggingFace 的 taobao-mnn 组织，逐个核对文件名 |
| U6 | `-DMNN_LOW_MEMORY=ON` 对 decode 速度的影响 | 低端机要不要开 | 同机型对比开/关两版 |
| U7 | `sample_us` 与 `decode_us` 是否重叠 | 避免重复计算 decode 速度 | 读 LLM 源码解码循环 |

---

## 7. 对 v2 方案文档的更正建议

1. 硬约束第 1 条里举的例子 `include/MNN/expr/Transformer.hpp` 应改为 `transformers/llm/engine/include/llm/llm.hpp`。
2. 7.1 止损点「优先用官方预编译 .so」应改为「MNN 无预编译，接受自编译是前置成本；止损线 = 编译失败超过 4 小时则改走 MnnLlmChat APK 抽 .so」。
3. 3.1 设备字段里的「Vulkan 支持」与 MNN 无关（MNN 走 OpenCL），应改为探测 **OpenCL / GPU 厂商 + fp16 支持**。
4. 阶段 2 的 Benchmark 五项指标，直接映射 `LlmContext`，不需要自己写计时器（内存峰值除外）。

---

## 8. 实测记录（2026-10-17 下午，本机 Windows + NDK 28）

| 项 | 结果 |
|---|---|
| 首次编译（未开 LLM） | **4 m 55 s**，`libMNN.so` **5.7 MB**，`Llm::` 符号 **0 个** |
| 二次编译（开 LLM） | **3 m 39 s**，`libMNN.so` **7.2 MB**，`Llm::` 符号 **59 个** |
| JNI 库 | `libliteagent_llm.so` **76 KB** |
| APK（debug） | **24 MB**（含 `libMNN.so` 7.49 MB） |
| 导出符号核对 | `nativeCreate` / `nativeGenerate` / `nativePerf` 均在 |

### 8.1 新发现的坑：`MNN_BUILD_LLM` 默认是 OFF

根 `CMakeLists.txt:78`：

```cmake
option(MNN_BUILD_LLM "Build llm library based MNN." OFF)
```

而官方的 `project/android/build_64.sh` **完全没有传这个开关**。

后果：照官方脚本编出来的 `libMNN.so` 不含 LLM 引擎，链接自己的 JNI 时必然 `undefined reference to MNN::Transformer::Llm::createLLM`。

这是"照官方脚本编译"这条路线上的隐形地雷——第一次编完会误以为成功了（BUILD SUCCESSFUL），直到链接阶段才炸。

必须加：

```cmake
-DMNN_BUILD_LLM=ON
-DMNN_LLM_BUILD_DEMO=OFF          # 不编 demo，省时间
-DLLM_SUPPORT_HTTP_RESOURCE=OFF   # 不从 URL 拉模型，省体积
-DLLM_LOG_TO_STRING=ON            # 把 MNN 日志存字符串，便于错误上报
```

### 8.2 流式与中断的真实实现（补第 2.3 节的空白）

MNN **没有回调式 API**。`response(prompt, ostream*, end_with, max_new_tokens)` 是阻塞的，一调到底。

可中断的正确写法（来自 `llm_session.cpp:317-324`）：

```cpp
// 1. 只做 prefill，不产出 token（max_new_tokens = 0）
llm->response(messages, &os, "<eop>", 0);
// 2. 逐 token 解码，每个 token 之间都有机会退出
while (!stop && !finished && generated < maxTokens) {
    llm->generate(1);
    ++generated;
}
```

直接用 `response(..., maxTokens)` 的后果：停止按钮形同虚设，用户点了要等模型自己吐完。

### 8.3 未确认清单更新

| # | 状态 |
|---|---|
| U1 编译耗时与体积 | ✅ 4m55s / 3m39s，7.2 MB |
| U2 中断语义 | ✅ 单步解码已在 JNI 层实现，token 级可退出（待真机验证延迟） |
| U3 UTF-8 切分 | ✅ 已在 `JavaTokenSink` 实现按 UTF-8 首字节判长的切分（待中文实测） |
| U4 OpenCL 加速比 | ⬜ 待真机 |
| U5 模型下载源 | ⬜ 待核 |
| U6 `MNN_LOW_MEMORY` 影响 | ⬜ 待对比 |
| U7 `sample_us` 是否含在 `decode_us` 内 | ⬜ 待读源码 |
