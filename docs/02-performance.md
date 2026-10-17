# 02 · 启动与包体积

> 时间：2026-10-17
> 数据来源：本机构建产物实测，不是估算。

## 一、包体积：R8 之后少了 65%

| 构建类型 | APK 体积 | dex（未压缩） | native |
|---|---|---|---|
| debug | **24 MB** | 54.0 MB | 7.2 MB |
| release（R8 + shrinkResources） | **8.4 MB** | **1.97 MB** | 7.2 MB |

debug 包里屎山一样的 54MB dex 主要来自 Compose 和 lifecycle 的未裁剪符号，压缩后在 APK 里占 24MB。开 R8 之后 dex 只剩 1.97MB。

### release 包的体积构成

```
native   7.20 MB   ← 86%
dex      1.97 MB   ← 23%
other    0.13 MB
```

**native 占了 86%**，其中 `libMNN.so` 一个文件 7.49MB（未压缩）。

这个比例说明一件事：包体积优化已经到头了。继续压只有三条路，每条都有代价：

| 手段 | 能省 | 代价 |
|---|---|---|
| 去掉 OpenCL 后端 | 约 1–2 MB | 失去 GPU 加速，低端机 decode 掉一半 |
| 去掉 LLM 引擎 | 约 1.5 MB | 那就不是这个产品了 |
| 拆 ABI / 动态下发 | — | 已经只发 arm64，无可再拆 |

结论：8.4MB 对"自带 LLM 推理 + 本地检索 + Agent 工具"的功能规模是合理的。工具类产品的用户对 10MB 以内是能接受的（对比：甜气AI 8–12MB，纯云端）。

### R8 踩的三个坑

**1. kotlinx.serialization 全靠反射找 `$$serializer`**

不 keep 的话，运行时抛：

```
SerializationException: Serializer for class 'DocMeta' is not found
```

而且这个错只在**解析文件时**出现——App 能启动、界面能显示，点进资料库才崩。规则：

```proguard
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.liteagent.**$$serializer { *; }
-keepclasseswithmembers class com.liteagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

**2. JNI 类名里嵌了完整包名**

`Java_com_liteagent_app_engine_LlmEngine_nativeCreate` 这个名字里嵌了包名。R8 把包名混淆成 `a.b.c`，`System.loadLibrary` 之后 `nativeCreate` 就找不到实现，直接 `UnsatisfiedLinkError`。

所以必须 keep 住那个类：

```proguard
-keep class com.liteagent.app.engine.LlmEngine { *; }
```

这条特别容易被忽略——因为 debug 包一切正常，只有 release 才炸。

**3. JNI 回调方法名也会被混淆（最隐蔽的一个）**

这一条是装了 release 包才发现的：

```
I LiteAgentJNI: 模型加载完成, 耗时 4858 ms
E LiteAgentJNI: 回调缺少 onToken(String):Boolean
```

`env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z")` 是**按字符串查方法**。R8 把 `onToken` 改名成 `a`，查不到，返回 null，于是 native 层直接 return -2。

表现：模型加载成功、进程不崩、UI 正常，但**一个字都生成不出来**。debug 包完全正常。

```proguard
-keepclassmembers class * {
    boolean onToken(java.lang.String);
}
```

修完之后 release 包实测通过：加载 3976 ms、首 token 2268 ms、decode 5.9 tok/s。

**判断标准**：只要 Java 侧的方法名出现在 C++ 的字符串字面量里，就必须 keep。不止类名和 native 方法名，回调方法名同样在内。

## 二、启动路径：首帧之前做了什么

冷启动时，`Application.onCreate` → `ChatViewModel.init` → `bootstrap()`。

`bootstrap()` 里有三件重活：

1. 设备探测（`DeviceProfiler.collect()`）—— 读 `/sys/devices/system/cpu/*`、遍历 cpufreq、创建 EGL 上下文查 GPU 型号
2. 读 assets 的 `recommend-rules.json` 并解析
3. 检查模型目录下 7 个文件的存在与大小

这三件**全都在 `Dispatchers.IO` 上**。这是代码审查 P0 的修复项——最初版本直接在 `viewModelScope.launch`（默认 Main）里跑，会卡住首帧。

现在的行为：首帧立刻显示，状态卡片先显示"探测中…"，IO 完成后自动填充。用户感知是"打开就有界面"。

### EGL 探测的代价

`queryGlRenderer()` 要创建一个离屏 EGL 上下文。这个操作在部分机型上要 50–150ms，而且**必须在非主线程**。

代价换来的信息是 GPU 型号字符串（`Adreno (TM) 740` / `Mali-G715`）。Android 没有公开 API 能拿到它，而 MNN 的 GPU 后端是 OpenCL——判断某机型能不能走 GPU，只能靠这个字符串 + GLES 版本推断。

值得，因为它是"设备自适应"里唯一没法用现成 API 替代的一环。

### 一个还没做的优化

模型加载（`Llm::load()`，531MB~2.2GB 权重）目前在用户点"加载模型"时同步触发，走 IO 线程。首次加载在真机上预计 1–3 秒，期间 UI 只显示"模型加载中…"。

可以优化成：启动时预加载 + 进度条。但这会让冷启动变慢，而且用户可能根本不想加载模型。当前的"按需加载"是更合理的取舍。

## 三、待真机验证的指标

以下全部**没有数据**，必须真机跑：

| 指标 | 测量方式 | 关注阈值 |
|---|---|---|
| 冷启动到首帧 | `adb shell am start -W` 的 `TotalTime` | < 800ms |
| 模型加载耗时 | `LlmContext.load_us`（跑分页已展示） | 0.5B < 1.5s |
| 首 token 延迟 | `LlmContext.ttfa_us` | < 3s |
| decode 吞吐 | `gen_seq_len / decode_us` | > 8 tok/s |
| 内存峰值 | `/proc/self/status` 的 `VmHWM` | < 可用内存 60% |
| 连续生成 3 分钟后的吞吐衰减 | 前 100 / 后 100 token 的 tok/s 比值 | > 0.6 |

最后一项是手机上真正的问题：SoC 降频后 decode 会掉一半，而大部分人只报峰值。
