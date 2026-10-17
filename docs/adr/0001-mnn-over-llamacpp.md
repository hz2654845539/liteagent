# ADR 0001 · 为什么用 MNN，而不是 llama.cpp

- 状态：已采纳
- 日期：2026-10-17

## 背景

端侧 LLM 在 Android 上的主流方案是 llama.cpp（GGUF 格式）。调研了一圈开源项目：PocketPal、MobileClaw、opendroid 全部基于它，生态最成熟。

摆在面前的选择是"跟着主流走"还是"选一个更少人走的路"。

## 决策

选 **MNN**。

## 理由

1. **中文小模型的官方量化版本现成**。MNN 团队在 ModelScope 上维护了 Qwen 系列的预转换权重（0.5B/1.5B/3B），开箱即用；llama.cpp 的 GGUF 中文模型多为社区转换，质量参差。
2. **性能计数器内置**。`LlmContext` 直接给 `load_us / prefill_us / decode_us / ttfa_us`，不需要自己在 decode 循环里插桩。
3. **做端侧 LLM Agent 的开源项目里，MNN 是空白位**。这不是技术理由，但对一个要公开展示的项目有意义——有东西可写。

## 代价

**必须自己编译。** MNN 主仓库不发布 Android 预编译 `.so`（`libs/`、`jniLibs/` 都是空的），官方 `build_64.sh` 还是给 unix `make` 写的。

实测成本：Windows + NDK 28 + Ninja，改 4 个开关后 **3 分 39 秒**出 `libMNN.so`（7.2MB）。比预想的 1-2 天低一个数量级，所以这个代价可以接受。

## 放弃的方案

**llama.cpp + GGUF**：生态好、预编译库现成，但中文量化模型要自己挑，且没有内置性能计数器。

**LiteRT-LM**：Google 官方，但中文小模型支持不如 MNN，且生态主要在 Gemma 系。

## 后果

- 引入了一个非主流技术栈，团队接手成本更高（需要读 MNN 源码）
- 换来了中文场景的开箱可用和更细的性能观测
- 如果后续要支持 GGUF 模型，需要再写一层适配——目前不做

## 如果这个决策被推翻

触发条件：MNN 在目标机型上的 decode 速度低于 5 tok/s，或者量化精度导致中文回答质量不可接受。届时切 llama.cpp 的迁移成本主要在 JNI 层（`llm_jni.cpp` 是唯一直接碰 MNN 的地方，约 300 行）。
