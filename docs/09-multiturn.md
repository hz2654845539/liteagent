# 09 · 多轮对话：一次实验挖出两个真 bug

> 时间：2026-10-17 21:50
> 起因：做前缀缓存实验时，发现 reuse_kv 开不开没差别
> 结果：**这个项目此前根本没有多轮对话能力**，每轮都是孤立问答。

---

## 一、怎么发现的

原本想验证 MNN 的 `reuse_kv`（KV cache 复用，默认 `false`）能省多少 prefill。设计了两组对照：

| | turn 1 | turn 2 |
|---|---|---|
| reuse_kv=0 | ttft 1702 ms | ttft 1260 ms |
| reuse_kv=1 | ttft 1706 ms | ttft 1254 ms |

**两组完全一样，而且第二轮比第一轮还快。**

如果真是"每轮重算全部历史"，第二轮应该更慢才对。反常即信号——于是去看 `preparePrompt`。

## 二、bug 1：根本没带历史

```kotlin
private suspend fun preparePrompt(input: String): PreparedPrompt {
    val preset = PresetStore.find(_state.value.presetId)
    var system = preset.systemPrompt + ... // 拼工具 schema、skill

    val hits = ragEngine.retrieve(input)
    if (hits.isEmpty()) return PreparedPrompt(input, system, emptyList())

    system += "..."
    return PreparedPrompt(ragEngine.buildPrompt(input, hits), system, hits)
}
```

返回的 `PreparedPrompt(input, ...)` 里，`input` 就是当前这一句。**整个函数没有任何地方读 `_state.value.messages`。**

所以发给 MNN 的永远只有两条：

```
system: 你是一个运行在手机本地的 AI 助理...
user:   我刚才让你记住的数字是多少？
```

界面上消息一条条堆着，看起来是多轮对话；模型那边每轮都是第一句话。**"再说一遍""刚才那个"这类指代，从来就不可能成立。**

### 为什么之前没发现

因为单轮问答一切正常。RAG 测试问的是"量化精度是多少"，这个问题自带完整语义，不需要上下文。只有做多轮基准时，异常才暴露。

## 三、bug 2：当前问题被发了两遍

修 bug 1 时，`buildMessages` 第一版是这样写的：

```kotlin
val history = all.dropLast(1)   // 以为最后一条是刚加的用户消息
```

但 `send()` 里先 append 用户消息、再 append 一条**空的 assistant 占位**（等流式填充）。所以 `dropLast(1)` 去掉的是那个空占位，用户消息还在。

日志直接打出来了：

```
buildMessages size=5 detail=system:48,user:14,assistant:384,user:6,user:6
                                                        ↑        ↑
                                                     同一句话两遍
```

模型收到「记住7391」+「是多少」+「是多少」。回答是「这个数字是什么？」重复 48 次。

修法：

```kotlin
val trimmed = all.dropLastWhile { it.content.isBlank() }   // 剥掉尾部空占位
val lastUserIdx = trimmed.indexOfLast { it.role == ChatRole.USER }
val history = trimmed.filterIndexed { idx, msg ->
    idx != lastUserIdx && msg.content.isNotBlank()
}
```

## 四、JNI 也要改

MNN 的 `response(ChatMessages)` 支持多轮，但我们原来的 JNI 只收 `prompt + systemPrompt` 两个字符串：

```cpp
nativeGenerate(handle, prompt, systemPrompt, maxTokens, callback)
```

改成两个等长数组：

```cpp
nativeGenerate(handle, roles[], contents[], maxTokens, callback)

// C++ 侧
for (jsize i = 0; i < count; ++i) {
    messages.emplace_back(jstr(role), jstr(content));
}
```

**为什么不在 Kotlin 侧拼成一整段字符串**：MNN 会按模型自带的 `prompt_template` 逐条套 `<|im_start|>role\ncontent<|im_end|>`。自己拼成一整段，模型看到的还是"一条超长用户消息"，多轮结构丢失，等于没修。

## 五、验证

任务：先说「记住7391」，再问「我刚才说的数字是多少」。

修复前的日志与回答：

```
buildMessages size=5  detail=system:48,user:14,assistant:384,user:6,user:6
回答：这个数字是什么？这个数字是什么？这个数字是什么？……（重复 48 次）
```

修复后：

```
buildMessages size=4  detail=system:48,user:6,assistant:192,user:10
回答：答案：7391 4272497 77777777……
```

**7391 出来了。** 后面那串 `42724977777` 是 0.5B 的退化重复，属于模型能力问题，与链路无关——但它至少先答对了。

## 六、代价：历史不是免费的

这台设备 prefill 只有 15–25 tok/s。

| 历史长度 | 额外 prefill |
|---|---|
| 100 字 | ~4 秒 |
| 300 字 | ~12 秒 |
| 600 字（当前上限） | ~25 秒 |

所以 `buildMessages` 有字符预算：

```kotlin
const val HISTORY_CHAR_BUDGET = 600
```

从最近往前取，超了就丢最早的。600 字大约 400 token，是"能记住上一轮、又不至于把 prefill 拖爆"的折中。

实测两轮的 ttft：9252 ms / 9100 ms——第二轮并没有比第一轮慢，因为第一轮的 assistant 回答只有 192 字，加上去和第一轮的 system+工具提示差不多。

## 七、这件事说明的问题

一个看起来"功能齐全"的应用（会话列表、消息气泡、流式输出、引用标注都有），**核心的多轮能力是假的**。

它不是崩溃、不是报错、不是界面异常——**它只是安静地每轮重新开始**。单轮测试 100% 通过，只有设计一个"必须依赖上文"的任务才会露馅。

这类缺陷比编译错误危险得多，因为它伪装成了"模型能力差"。这次要不是不信"第二轮比第一轮还快"这个反常数据，大概会一直以为是 0.5B 记不住。

## 八、还没做的

1. **reuse_kv 没开**。现在历史真的进了 prompt，每轮重算的成本变高了，KV 复用才有意义。源码确认支持（`llmconfig.hpp:165`，默认 false），但这次两组数据是"没带历史"时测的，需要重测。
2. **历史截断太粗暴**。按字符数切，不看语义边界，可能把一轮对话切成半句。应该按消息单位取整轮。
3. **没有摘要压缩**。超出预算就丢最早的，正确做法是把早期对话压成一句摘要。
