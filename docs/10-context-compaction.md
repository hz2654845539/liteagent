# 10 · 上下文压缩：用摘要代替丢弃

> 时间：2026-10-17 22:19
> 起因：历史预算砍到 300 字后，超出的部分直接丢——用户问"第一轮聊了什么"就断片
> 结论：压缩生效（541 字 → 87 字摘要），但它解决的是"无限增长"，不是"变快"

---

## 一、要解决的问题

`docs/09` 测出来：多轮对话的代价是 10 倍首 token 延迟（1.7s → 17s）。KV 复用走不通，只能砍历史预算到 300 字。

但砍完出现新问题：**超出 300 字的部分被直接丢掉**。

后果很具体：

- 第 4 轮问"最开始我们定的方案是什么"，模型答不出来
- 用户看到对话记录还在，以为模型记得，实际早被截断了

**丢弃和遗忘的区别是：丢弃是无声的。** 用户不知道哪些内容已经不在上下文里了。

## 二、方案

把早期对话压成一句摘要，而不是扔掉。

```kotlin
data class Session(
    ...
    val summary: String = "",          // 早期对话的摘要
    val summarizedCount: Int = 0       // 前 N 条已被摘要覆盖
)
```

**关键设计：摘要不删消息。**

- 界面上消息一条不少（用户看到的还是完整对话）
- 发模型时，前 `summarizedCount` 条跳过，改发一句摘要

这样用户体验不变，模型拿到的是"摘要 + 近期原文"。

组装逻辑（`ChatViewModel.buildMessages`）：

```
system      ← 人格 / 工具 schema
assistant   ← 之前对话的摘要：<summary>
user        ← 未摘要的历史（按预算取最近若干条）
...
user        ← 当前问题
```

## 三、什么时候压

发送前检查，未摘要部分超过 `COMPACT_TRIGGER_CHARS`（300 字）就压掉最早的一半。

用模型自己压（它刚见过这些内容），生成上限 `COMPACT_MAX_TOKENS = 80`。

**为什么不用后台异步压缩**：压缩和正常生成抢同一个引擎实例，并发会互相踩状态（encoder reset、KV 清空）。宁可让用户在这一轮多等 8 秒，也不要在后台制造一个不可复现的竞态。

## 四、实测

三轮对话，日志逐轮记录：

```
turn=1  buildMessages size=2  system:48,user:12
        ttft=1802ms    prefill=19.6tps

turn=2  buildMessages size=4  system:48,user:12,assistant:232,user:13
        ttft=9155ms    prefill=25.4tps

Compact: compacted=2 chars=541 -> summary=87 total_summarized=2

turn=3  buildMessages size=5  system:48,assistant:95,user:13,assistant:272,user:12
        ttft=12477ms   prefill=25.6tps
```

**压缩把 541 字的两条消息压成了 87 字**，省掉 454 字（84%）。

从 turn=3 的 `detail` 能看出来：原本的 `user:12,assistant:232` 两段（244 字）被替换成了 `assistant:95`（摘要），但保留了较近的 `assistant:272`。

## 五、一个诚实的说明：压缩不等于变快

turn=3 的 ttft 是 12477ms，比 turn=2 的 9155ms **还高**。

不是因为压缩没用，而是因为：

- turn=2 的历史只有 232 字
- turn=3 的历史 = 摘要 95 字 + 保留的 272 字 = 367 字

**压缩压掉的是"最早期"的部分，越新的内容越完整地保留**——这是设计意图（近期上下文比早期摘要更重要），代价就是总量还在缓慢增长。

压缩真正解决的是**无限增长**：没有它，第 10 轮的上下文会是第 3 轮的三倍以上；有了它，总量被钉在"摘要 + 最近一轮"的量级。

**它不是加速器，是刹车。**

## 六、实现中踩的一个坑

第一版把"当前正在问的问题"也算进了压缩候选：

```kotlin
val pending = all.drop(session.summarizedCount).filter { it.content.isNotBlank() }
```

`pending.size / 2` 因此多算一条，而且**当前问题有被压进摘要的风险**——模型会丢掉眼前要回答的东西。

修法：明确排除最后一条用户消息。

```kotlin
val currentUser = all.lastOrNull { it.role == ChatRole.USER }
val pending = all.drop(session.summarizedCount)
    .filter { it.content.isNotBlank() && it.id != currentUser?.id }
```

## 七、还没做的

1. **阈值偏低**。300 字触发太频繁，一次只压一半，等于每两轮压一次、每次多等 8 秒。应该提到 600 左右，一次压掉更多。
2. **摘要是单句拼接**。多次压缩后用"；"连起来，到后面会变成一串没有逻辑关系的短句。应该做成"重新总结已有的摘要 + 新内容"，而不是追加。
3. **摘要质量没评估**。没验证过 50 字摘要能不能覆盖关键事实（比如具体的数字、方案名）。这需要一套固定问答集来测。
4. **没有手动入口**。用户不能主动"清空上下文但保留摘要"，也不能查看摘要内容。
