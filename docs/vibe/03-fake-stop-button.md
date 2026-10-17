# 案例 3 · 停止按钮是假的

> 时间：2026-10-17 · 阶段：流式输出

## 需求

聊天要流式输出，并且用户点"停止"能立刻停。

## 我给的 prompt

```
用 MNN 的 Llm 实现流式生成：token 逐个回调到 Kotlin，支持中途停止。
```

## AI 首版输出

```cpp
std::ostream os(&sink);                    // 自定义 streambuf 回调 Kotlin
llm->response(prompt, &os, nullptr, 512);  // 一次调到底
```

看起来对——std::ostream 接管输出，每写一个 token 就回调。停止标志在 sink 里检查。

## 问题

`sink` 里检查到 `stop == true` 就 `return` 不输出，但 **`response()` 本身还在跑**。

它的签名是 `void response(prompt, ostream*, end_with, max_new_tokens)`，同步阻塞到生成结束。用户点了停止，UI 立刻不刷新了，看起来像停了，但 CPU 还在满负载解码，手机上表现为发烫、掉电，而且这段时间内不能再发下一条消息。

更糟的是：如果用户以为停了又发一条，两个 `response` 会并发跑同一个 Llm 实例——直接崩。

## 修正 prompt

```
MNN 没有回调式 API，response 是阻塞的。
去看 apps/Android/MnnLlmChat 里 LlmSession::Response 是怎么做到可中断的，
把真实代码贴出来，不要自己设计。
```

## 最终方案

它在 `llm_session.cpp:317-324` 找到了答案：

```cpp
// 1. 只做 prefill，不产出 token
llm->response(messages, &os, "<eop>", 0);
// 2. 逐 token 解码，每个 token 之间都能退出
while (!stop && !finished && generated < maxTokens) {
    llm->generate(1);
    ++generated;
}
```

关键在 `max_new_tokens = 0`——这个参数让 `response` 只建 KV cache 不生成。

改成单步后，最坏停止延迟 = 一个 token 的解码时间（约 100ms），用户感知就是"立刻停"。

## 这个案例说明什么

**"看起来对"和"行为对"是两件事。** AI 首版代码在静态审视下挑不出毛病：类型对、参数对、流式也算"流式"。但它没有回答真正的问题——中断到底发生在哪一层。

发现这类问题靠的不是读代码，而是问自己一句："用户点下去之后，到底哪个循环退出了？"
