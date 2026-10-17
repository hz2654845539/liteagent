# 案例 4 · 8GB 手机被推荐了 0.5B 模型

> 时间：2026-10-17 · 阶段：设备分级推荐

## 需求

按手机内存推荐模型：内存越大用越大的模型。

## 我给的 prompt

```
写一个设备分级规则：< 6GB 用 0.5B，6-8GB 用 1.5B，8-12GB 用 3B，>= 12GB 可试 7B。
字段用 ActivityManager.MemoryInfo.totalMem。
```

## AI 首版输出

```kotlin
when {
    totalMemMb < 6144 -> "0.5B"
    totalMemMb < 8192 -> "1.5B"
    totalMemMb < 12288 -> "3B"
    else -> "7B"
}
```

逻辑清晰，边界明确，直接可以跑。

## 问题

我拿真机数据一验就发现不对。`MemoryInfo.totalMem` 报的**不是标称容量**，内核和保留内存要先吃掉 0.4–0.8GB：

| 标称 | 实测 totalMem | 落在哪一档 |
|---|---|---|
| 6GB | ~5734 MB | `< 6144` → **0.5B** |
| 8GB | ~7680 MB | `6-8GB` → 1.5B ✓ |
| 12GB | ~11674 MB | `8-12GB` → 3B ✓ |
| 16GB | ~15872 MB | `>= 12288` → 7B |

两个后果：
1. **6GB 手机被无谓降级**——它明明跑得动 1.5B，却拿到 0.5B，体验差一大截；
2. **`>= 12GB` 那一档在 12GB 机器上永远命中不了**，只有 16GB 才进去。写成规则表时，这一行看起来合理，实际是死代码。

## 修正 prompt

```
分级阈值不能直接比 totalMem。先补偿系统占用，再就近归并到标称档位 {4,6,8,12,16}。
并且给这条规则写单元测试，用例必须用真实机型的实测值，不是我自己编的数。
```

## 最终方案

```kotlin
fun nominalGb(totalMemMb: Long): Int {
    val adjusted = totalMemMb / 1024.0 + RESERVED_MEM_GB   // 0.6
    return tiers.minByOrNull { abs(it.nominalGb - adjusted) }!!.nominalGb
}
```

配 10 个边界用例：

```kotlin
@Test fun `6GB 机不能掉到 0_5B`() {
    val result = engine.recommend(profile(5734)) as Recommendation.Supported
    assertEquals("qwen2.5-1.5b-instruct-mnn", result.model.modelId)
}
```

后端 Python 侧写了同构实现和同一组用例——两份代码，同一把尺子。

## 这个案例说明什么

AI 写的规则**在纸面上永远是对的**，因为它是从"标称容量"这个概念出发推理的，而真实设备的 API 返回的是另一个值。

这类 bug 不会报错、不会崩，只会让产品在老设备上莫名变笨。唯一的发现方式是：**拿真实数据去跑边界**。
