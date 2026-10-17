# 案例 2 · BUILD SUCCESSFUL，但编出来的库是空的

> 时间：2026-10-17 · 阶段：编译 libMNN.so

## 需求

按 MNN 官方脚本编译 Android 版 `libMNN.so`，要能跑 LLM。

## 我给的 prompt

```
按 MNN 仓库 project/android/build_64.sh 的方式，编译 arm64-v8a 的 libMNN.so。
Windows 环境，用 Ninja。
```

## AI 首版输出

一个完整的 `build_mnn_android.sh`，参数照抄官方脚本，`cmake --build` 成功：

```
[662/662] Linking CXX shared library libMNN.so
BUILD SUCCESSFUL
-rw-r--r-- 1 dnake 197121 5.7M libMNN.so
```

看起来完全没问题。5.7MB，链接成功，零错误。

## 问题

我多问了一句："这个 so 里到底有没有 LLM？"

```
llvm-nm -D --defined-only libMNN.so | grep createLLM
（无输出）
```

**一个 LLM 符号都没有。**

原因在 MNN 根 `CMakeLists.txt:78`：

```cmake
option(MNN_BUILD_LLM "Build llm library based MNN." OFF)
```

默认 OFF，而官方的 `build_64.sh` **从头到尾没传这个开关**。也就是说照官方脚本编出来的库，根本不带 LLM 引擎。

如果继续往下写 JNI，会在链接阶段炸在 `undefined reference to MNN::Transformer::Llm::createLLM`，而且完全不知道问题出在哪——因为编译环节一切正常。

## 修正 prompt

```
编译类任务，验收标准不是 "BUILD SUCCESSFUL"，而是"产物里有我要的符号"。
每次编完必须跑一遍 nm 检查导出符号，把结果贴出来。
```

## 最终方案

```cmake
-DMNN_BUILD_LLM=ON
-DMNN_LLM_BUILD_DEMO=OFF          # 不编 demo
-DLLM_SUPPORT_HTTP_RESOURCE=OFF   # 不从 URL 拉模型
-DLLM_LOG_TO_STRING=ON            # 日志可回捞
```

重编：3m39s，7.2MB，**59 个 `Llm::` 符号**。

## 这个案例说明什么

编译通过是最弱的验收标准。**AI 会把"命令没报错"当成"任务完成了"**，而工程上的失败往往发生在下一步。所以后来我给所有编译类任务加了硬指标：产物 + 符号 + 体积，三样对得上才算完。
