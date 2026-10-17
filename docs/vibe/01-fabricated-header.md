# 案例 1 · AI 编造了一个不存在的头文件

> 时间：2026-10-17 · 阶段：MNN 集成前期

## 需求

把 MNN 的 LLM 引擎接进 Android 工程，先搞清楚要 include 什么。

## 我给的 prompt

```
MNN 的 LLM 在 Android 上怎么调用？给出 C++ 的入口头文件和创建模型的方式。
```

## AI 首版输出

```cpp
#include "MNN/expr/Transformer.hpp"

auto llm = MNN::Transformer::Llm::createLLM(modelPath);
llm->load();
```

看起来非常合理——命名空间、类名、方法名都符合我对 MNN 的印象。

## 问题

我 clone 了 MNN 仓库去核对，`include/MNN/expr/` 下**根本没有 Transformer.hpp**。

实际路径是：

```
transformers/llm/engine/include/llm/llm.hpp
```

也就是说，如果直接照它写的代码去 include，第一步就编译失败。而且它不是随便编的——`MNN::Transformer` 这个命名空间是对的，`createLLM` 也是真方法，**只有路径是编的**。这种半真半假的错误最难发现。

## 修正 prompt

```
规则：任何 MNN 相关的代码，必须先 clone 仓库并在本地 grep 确认。
输出时要给出「文件路径 + 行号」。
找不到就写「未确认，需人工核实」，禁止猜。
```

## 最终方案

写入 `AGENTS.md` 作为硬约束第一条，并在 `docs/00-research.md` 里建立"真实 API 契约"表格：

| 动作 | 签名 | 行号 |
|---|---|---|
| 创建 | `Llm::createLLM(config_path)` | llm.hpp:161 |
| 加载 | `Llm::load()` | llm.hpp:165 |
| 推理 | `Llm::response(content, ostream*, end_with, max_new_tokens)` | llm.hpp:183 |

## 这个案例说明什么

**AI 不是不知道 MNN，它是把"知道的东西"和"猜的东西"用同一种语气说出来了。**
防不住这一点，整个项目的地基就是歪的。所以后来我要求它每条结论都标注【已核实·附路径】/【高置信推测】/【未确认】。
