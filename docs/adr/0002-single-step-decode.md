# ADR 0002 · 流式生成用单步解码，而不是一次 response

- 状态：已采纳
- 日期：2026-10-17

## 背景

MNN 的 LLM 接口只有一个阻塞式入口：

```cpp
void Llm::response(const std::string& content, std::ostream* os, const char* end_with, int max_new_tokens);
```

没有回调 API，没有 cancel 句柄。而产品要求"流式输出 + 点停止立刻停"。

## 决策

把生成拆成两步：

```cpp
llm->response(messages, &os, "<eop>", 0);   // max_new_tokens = 0，只做 prefill
while (!stop && !finished && n < maxTokens) {
    llm->generate(1);                        // 一次一个 token
    ++n;
}
```

## 理由

1. **`max_new_tokens = 0` 让 `response` 只建 KV cache 不产出 token**，这是官方 demo 里没写、但 MnnLlmChat 实际使用的用法。
2. **`generate(1)` 之间是检查停止标志的唯一机会**。一次 `response(..., 512)` 调用中间没有任何钩子，只能等它自己跑完。

实测最坏停止延迟 = 一个 token 的解码时间（约 100ms），用户感知是"立刻停"。

## 代价

- 每 token 一次 C++ → C++ 的函数调用开销（相对解码本身可忽略）
- 需要自己判断结束：`<eop>` 标记 + `LlmStatus` 双保险
- 代码比一行 `response` 复杂，需要注释说明为什么这么写

## 放弃的方案

**软停止**：用户点停止后不再往 UI 推 token，但后台继续解码到底。
否决理由：手机上表现为发烫、掉电；而且这段时间内 LlmEngine 仍被占用，用户再发一条会导致两个 `response` 并发跑同一个实例——直接崩。

**抛异常中断**：在 streambuf 里抛 C++ 异常穿透 MNN 栈。
否决理由：MNN 用 `-fno-exceptions` 编译，跨栈展开经过没有 unwind table 的帧是未定义行为。为了一个停止按钮引入崩溃风险不划算。

## 后果

停止是"token 级"而非"指令级"——最坏要等一个 token。在 10-20 tok/s 的设备上是 50-100ms，可接受；如果将来上到更快的模型，这个延迟会更短。
