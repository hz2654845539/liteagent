# ADR 0004 · 设备分级规则只有一个数据源

- 状态：已采纳
- 日期：2026-10-17

## 背景

推荐逻辑需要同时存在于两端：

- **客户端**：断网时也要能推荐，不能依赖后端
- **后端**：发现某机型需要特殊参数时，改配置就能生效，不用发版

直觉做法是两边各写一份：Kotlin 一个 `when`，Python 一个 `if`。

## 决策

**数据单一源 + 算法双实现**：

- `rules/recommend-rules.json` 是唯一的数据源，客户端构建时同步进 assets，后端运行时读同一个文件
- 算法（内存归并、线程数、GPU 判断）两端各实现一份，但**用同一组边界用例约束**

## 理由

纯数据（阈值、档位表、模型名）是最容易漂的部分，把它收敛到一个文件就能消掉 80% 的漂移风险。

算法没法共享——Kotlin 和 Python 之间不存在共享代码的方式。硬要统一只能：
- 让后端调客户端（倒过来了）
- 或者写一套 DSL 两边解析（过度设计）

所以接受"算法写两遍"，但用测试钉住。

## 具体做法

```
rules/recommend-rules.json
    ├── 构建时 Copy → app-android/build/generated/rules/ → assets/rules/
    └── 运行时读    → backend/app/main.py
```

Gradle 里：

```kotlin
val syncRecommendRules by tasks.registering(Copy::class) {
    from(rootProject.file("rules"))
    into(layout.buildDirectory.dir("generated/rules"))
}
tasks.named("preBuild").configure { dependsOn(syncRecommendRules) }
```

## 怎么防止算法漂移

**同一组边界值，两边都测**：

| 实测 totalMem | 期望档位 | Kotlin 用例 | Python 用例 |
|---|---|---|---|
| 3584 | 4GB | ✅ | ✅ |
| 5734 | 6GB | ✅ | ✅ |
| 7680 | 8GB | ✅ | ✅ |
| 11674 | 12GB | ✅ | ✅ |
| 15872 | 16GB | ✅ | ✅ |

另外加了一组**跨文件一致性测试**（`backend/tests/test_catalog.py`）：规则表里出现的每个 `modelId` 必须存在于模型清单里。这条测试在改 ID 之后立刻抓到了两处不一致。

## 后果

- 加一个字段要改三处：JSON、Kotlin 数据类、Pydantic 模型
- 换来了"改阈值不用动代码"和"两端不会给出不同答案"

## 如果将来要彻底消除双实现

把推荐逻辑做成后端唯一实现，客户端只做缓存 + 回落。但那样断网首次启动就没有推荐了——与"完全离线"的产品前提冲突。所以现在的取舍是合理的。
