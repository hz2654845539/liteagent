# 代码导览：从输入到回答，数据是怎么流动的

> 这份文档写给第一次读这个项目代码的人。
> 不按目录罗列，按「一次提问发生了什么」来讲。

---

## 一、一句话总览

```
用户打字 → 检索本地文档 → 拼成一段话 → 交给手机里的模型 → 一个字一个字吐出来
           （RAG）        （prompt）      （MNN 推理）        （流式输出）
```

整个项目就是把这五个环节做扎实，外加"什么样的手机该用多大的模型"这一层判断。

---

## 二、一次提问的完整路径

假设用户第 3 次提问："那它和 llama.cpp 比呢？"

### 第 1 步：进 send()

`ChatViewModel.send()` 是整个流程的入口。它先做三件事：

```kotlin
_state.update { it.copy(messages = it.messages + userMessage) }  // ① 把用户的话上屏
_state.update { it.copy(generating = true) }                     // ② 按钮变成"停止"
launch { ... }                                                   // ③ 开一个后台协程干活
```

> 💡 为什么用协程：推理是阻塞的，放主线程会卡死界面（ANR）。

### 第 2 步：要不要压缩历史

`maybeCompactHistory()` —— 如果聊了很久，先把早期对话压成一句摘要，免得越聊越慢。

> 详细原理见 `docs/10-context-compaction.md`

### 第 3 步：检索本地文档

`RagEngine.retrieve(问题)` —— 在用户导入的文档里找相关段落。

用的是 **BM25 算法**（一种老牌的文本相关性排序方法），中文按"两个字一组"切词（bigram）。

找不到相关内容就返回空列表，走普通聊天；找到了就带上资料。

### 第 4 步：组装 prompt

`preparePrompt()` + `buildMessages()` —— 把这几样东西拼成一个列表：

```
system      : 你是一个运行在手机本地的 AI 助理……（人格设定）
assistant   : 之前对话的摘要：……               （早期对话压成的一句）
user        : 用一句话说明什么是 MNN            （上一轮的问题）
assistant   : MNN 是阿里开源的推理框架……        （上一轮的回答）
user        : 那它和 llama.cpp 比呢？           （这次的问题）
```

> 💡 为什么是"列表"而不是"一大段文字"：模型训练时见的就是这种分角色的对话格式。
> 拼成一整段，它会分不清谁说的，多轮指代（"它"指什么）就废了。

### 第 5 步：交给模型

`LlmEngine.generate(messages)` → JNI → MNN 的 `Llm::response()`

这一步在 C++ 里跑（`llm_jni.cpp`），因为推理是原生库。

MNN 会做两件事：
1. **prefill**：把上面所有文字一口气"读"进去（慢，每秒 25 字）
2. **decode**：一个字一个字往外蹦（每秒 7 个字）

### 第 6 步：流式上屏

模型每吐一个字，就通过回调传到 Kotlin，追加到界面上那条消息里。

```
C++ 每生成一个 token → 调用 Java 的 onToken(String) → 更新 UI
```

> 💡 回调返回 `true` 表示"别生成了"——用户点了停止按钮。

---

## 三、目录怎么分的

```
app-android/          Android 应用
├── cpp/              C++ 部分（JNI 桥接层，调用 MNN）
├── kotlin/
│   ├── capability/   设备探测 + 模型推荐（"这台手机能跑多大的模型"）
│   ├── data/         本地存储（模型文件、文档库、会话）
│   ├── engine/       推理引擎封装（LlmEngine）
│   ├── rag/          检索增强（切块、BM25、拼 prompt、引用）
│   ├── tool/         工具调用（提醒、剪贴板、日程）
│   ├── ui/           界面（Compose）
│   ├── vision/       图片识别（OCR）
│   └── benchmark/    性能测试
shared/               共享模型（Kotlin Multiplatform，将来 iOS 复用）
backend/              Python 后端（设备库、规则发布、崩溃上报）
rules/                模型推荐规则（JSON，可远程更新）
tools/                开发脚本（编译 MNN、下载模型、跑分扫描）
```

---

## 四、最该先读的五个文件

| 文件 | 一句话说明 | 为什么重要 |
|---|---|---|
| `ui/ChatViewModel.kt` | 整个应用的大脑 | 一次提问的全部流程都在这里，读它就懂全貌 |
| `engine/LlmEngine.kt` | 对 MNN 的 Kotlin 封装 | 看 JNI 怎么调、流式怎么实现 |
| `cpp/llm_jni.cpp` | C++ 桥接层 | 看 Java 和 C++ 怎么互相调用 |
| `capability/RecommendEngine.kt` | 设备分级 | 看"什么手机跑什么模型"的判断逻辑 |
| `rag/RagEngine.kt` | 检索实现 | 看 BM25 + 中文 bigram 怎么落地 |

### 建议的阅读顺序

1. **先看 `docs/` 里的架构决策**（`docs/adr/`），了解为什么这么设计
2. **再看 `ChatViewModel.send()`**，跟着一次提问走一遍
3. **然后挑感兴趣的模块深入**（RAG / 工具 / 设备探测）
4. **最后看 `cpp/`**，那是性能相关的底层

---

## 五、几个"为什么这么写"的关键点

读代码时如果觉得某处奇怪，大概率是踩过坑：

| 代码位置 | 看起来奇怪的地方 | 原因 |
|---|---|---|
| `buildMessages()` | 为什么要 `dropLastWhile` 两次 | 消息列表末尾有个空的占位消息，不清理会发给模型 |
| `buildMessages()` | 为什么超长消息要截断而不是丢 | 0.5B 回答常有 600+ 字，整条丢掉会让历史为空 |
| `llm_jni.cpp` | 为什么 `onToken` 要 keep 住 | R8 混淆会改名，JNI 按字符串找不到方法，release 包静默失效 |
| `RecommendEngine` | 为什么线程数写死 3 | 实测 5 线程比 3 线程慢 10%（`docs/05`） |
| `ImageTextExtractor` | 为什么用 OCR 不用视觉模型 | 2GB 设备跑不动 2B 的 VLM（`docs/08`） |

---

## 六、想跑起来看效果

```bash
# 1. 编译 APK（仓库里已带预编译的 libMNN.so，不用装 NDK）
./gradlew :app-android:assembleDebug

# 2. 装到手机
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk

# 3. 下载模型（需要 python 解析清单）
bash tools/download_model.sh MNN/Qwen2.5-0.5B-Instruct-MNN

# 4. 推进设备
adb push models/Qwen2.5-0.5B-Instruct-MNN /sdcard/Download/
# 然后在 App 里选择模型目录导入
```

想直接改代码试效果，可以看 `MainActivity` 里的自测入口：

```bash
adb shell am start -n com.liteagent.app.debug/com.liteagent.app.MainActivity \
  --es autotest "你好"
```

---

## 七、这份文档本身

代码里每个关键函数都有注释，写法是「先讲人话、再讲技术、最后给数据」。

比如 `maybeCompactHistory()` 的注释开头是：

> 你跟朋友聊了两小时，现在要说正事。你不可能把两小时的每句话重讲一遍，
> 正常做法是："我们前面聊了 A 和 B，现在说 C。"

这样写的原因：**读代码的人大概率不知道什么是 prefill，但他知道聊天聊久了会记不住。**
