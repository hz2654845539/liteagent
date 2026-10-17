# 01 · 代码审查记录

> 时间：2026-10-17
> 范围：`app-android` 全部 Kotlin / C++，`backend`，`shared`
> 方式：自审。逐文件过一遍调用链，重点看**线程、生命周期、边界**三类问题。

约定：每条标注严重度。P0 会在线崩或卡死，P1 会导致结果错，P2 是体验/维护性问题。

---

## 发现的问题

### P0-1 · 模型加载在主线程执行

`ChatViewModel.loadModel()` 里：

```kotlin
viewModelScope.launch {                    // 默认 Dispatchers.Main
    val result = engine.init(...)          // 内部是同步阻塞的 JNI 调用
}
```

`engine.init` 会走到 `nativeCreate` → `Llm::load()`，加载 531MB 权重在手机上要几百毫秒到数秒。
在 Main 线程上执行，轻则掉帧，重则 ANR。

**修复**：`withContext(Dispatchers.IO)` 包住。

### P0-2 · 状态初始化在主线程做磁盘 IO

`bootstrap()` 里读 assets 的规则文件，又调 `store.isReady(entry)` 逐个 `File.length()`。
虽然只有 7 个文件，但它在 Main 线程，且发生在首帧之前。

**修复**：整段放进 `Dispatchers.IO`。

### P1-1 · 空规则表会抛 NPE

`RecommendEngine`：

```kotlin
?: rules.memTiers.minByOrNull { it.nominalGb }!!.nominalGb
```

如果 JSON 被改坏成 `memTiers: []`，这里直接 NPE，且发生在 UI 初始化路径上，用户看到的是闪退。

**修复**：空表时返回一个明确的 Unsupported，而不是崩。

### P1-2 · 分块重叠可能产生重复块

`TextChunker.appendSection` 的 `emit()` 会把最后一句留作下一块开头。
如果这一句本身接近 `targetSize`，下一个句子进来会立刻再触发 `emit()`，于是同一句话出现在两个块的**开头和结尾**，索引里就有了近似重复的条目，检索时会挤占 top-K。

**修复**：保留的重叠句总长超过 `targetSize / 2` 时，不再保留。

### P1-3 · 下载脏文件时没有清理 .part 残留

`downloadOne`：

```kotlin
if (target.exists() && target.length() != mFile.size) target.delete()
var existing = if (part.exists()) part.length() else 0L
```

如果 `target` 是坏的，但同名的 `.part` 里还留着**上一版模型**的数据，`existing` 会从一个错误的偏移开始续传，拼出来的文件大小可能恰好对上，SHA-256 校验才会拦住——但那时已经白下了几百 MB。

**修复**：删除 `target` 时同步删除 `.part`。

### P1-4 · SQLite 连接只提交不关闭

`backend/main.py`：

```python
with _db() as conn:
    ...
```

`sqlite3.Connection` 的上下文管理器只管事务提交，**不关连接**。每个请求泄漏一个连接，压测时很快耗尽。

**修复**：显式 `try/finally: conn.close()`。

### P2-1 · 每个 token 都做位移动画

`ChatScreen` 的 `LaunchedEffect` 用 `animateScrollToItem`，而 key 里包含 `content.length`，等于每来一个 token 就启动一次滚动动画。长回答时滚动会和动画打架，出现"追不上"的观感。

**修复**：改成 `scrollToItem`（立即定位）。

### P2-2 · `perf!!` 强解包

```kotlin
val perf = state.perf
if (state.perf != null) { perf!!.ttftMs }
```

能编过，但 `!!` 在这里是拿可空性换省事。改成 `let`。

### P2-3 · `Bm25Index` 的 `add` 单条不重建统计

`add()` 只追加，`rebuildStats()` 要手动调。调用方忘了就会得到**全部 0 分**的检索结果——而且是静默的，不报错。

**修复**：加 `dirty` 标记，`search` 时自动重建。

### P2-4 · 后端与客户端的推荐算法是两份实现

`recommend-rules.json` 保证了**数据**单一源，但 `nominalGb` / `pickThreads` / `canUseGpu` 的逻辑两边各写了一遍。改一处忘另一处就会漂移。

这不是能立刻消除的（Kotlin 与 Python 无法共享代码），但必须承认它，并在 README 里写明"靠同一组边界用例约束"。当前只在 Kotlin 侧有测试，Python 侧**没有**。

**修复**：给后端补一组等价的边界测试。

---

## 未发现但值得记录的取舍

| 项 | 现状 | 为什么这样 |
|---|---|---|
| `NewStringUTF` 处理 emoji | 4 字节 UTF-8 可能乱码 | Java 的 `NewStringUTF` 期望 modified UTF-8。中文 3 字节没问题，emoji 会坏。要彻底解决需要用 `NewString(jchar*)` 手工转 UTF-16。**已知限制**。 |
| JNI 回调逐 token 跨语言 | 每 token 一次 JNI 调用 | 10-20 tok/s 的频率下开销可忽略；如果换到 50+ tok/s 的大模型需要做批量聚合。 |
| `LlmEngine` 没有并发保护 | 同时调两次 `generate` 会崩 | 上层（ViewModel）已经用 `generating` 状态挡住。属于"约定优于防御"，后续要加锁。 |
