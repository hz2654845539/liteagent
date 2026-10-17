# CLAUDE.md

完整的项目规则在 [AGENTS.md](AGENTS.md)，动手前先读它。这里只列最高优先级、违反一次就会浪费几小时的约束。

## 三条不可违反

1. **MNN 相关代码必须先查 `.refs/MNN` 再写。**
   查不到就写「未确认」，禁止凭记忆编 API。曾经编出过一个不存在的 `include/MNN/expr/Transformer.hpp`，见 `docs/vibe/01-fabricated-header.md`。

2. **编译类任务的验收标准不是 `BUILD SUCCESSFUL`，是产物里有你要的符号。**
   曾经编出一个 5.7MB 的 `libMNN.so`，编译零错误，里面一个 LLM 符号都没有——因为少传了 `-DMNN_BUILD_LLM=ON`。见 `docs/vibe/02-empty-so.md`。

3. **不允许留 TODO 后宣称完成。** 编译不过、测试不过，都不算完成。

## 已核实的 MNN 契约（照此写，别改）

```
头文件   transformers/llm/engine/include/llm/llm.hpp
创建     Llm::createLLM(configPath) → set_config(json) → load()
推理     Llm::response(content, std::ostream*, end_with, max_new_tokens)   // 阻塞，无回调
中断     不能用 response 一次跑完。必须 response(..., 0) 做 prefill + while{ generate(1) }
性能     LlmContext.ttfa_us / prefill_us / decode_us / load_us
GPU      OpenCL（-DMNN_OPENCL=ON），不是 Vulkan
编译     tools/build_mnn_android.sh（必须在 Windows 上用 Ninja，没有 make）
```

## 改代码前先看哪里

| 想改什么 | 先读 |
|---|---|
| MNN / JNI | `docs/00-research.md` |
| 推荐规则 | `rules/recommend-rules.json` + `docs/adr/0004` |
| 检索 / 分块 | `docs/adr/0003` |
| 线程 / 生命周期 | `docs/01-code-review.md`（踩过的坑都在这儿） |

## 常用命令

```bash
bash tools/build_mnn_android.sh                 # 编 libMNN.so（约 4 分钟）
bash tools/download_model.sh                    # 下 0.5B 模型（531MB）
./gradlew :app-android:testDebugUnitTest        # 42 个单测
cd backend && python -m unittest discover -s tests   # 13 个测试
```

## 风格

- 注释解释**为什么**，不是做了什么
- 新增依赖前先说体积代价
- 单次改动不超过 3 个文件
