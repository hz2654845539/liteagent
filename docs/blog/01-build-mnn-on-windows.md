# 在 Windows 上把 MNN 编成 Android 库：四个文档里没写的坑

> 环境：Windows 11 / NDK 28.0.12433566 / CMake 3.30.5 / MNN commit `dbf9ba6`（2026-10-15）
> 结论：**3 分 39 秒**出一个 7.2MB 的 `libMNN.so`，含 59 个 LLM 符号。

MNN 是阿里开源的推理框架，端侧 LLM 支持做得不错。但官方文档对"怎么在 Android 上用 LLM"这件事，写得比想象中含糊。这篇文章记录我实际踩到的四个坑，每个都卡了我一段时间。

## 坑一：根本没有预编译库

大部分端侧框架都提供现成的 `.so` 或 AAR，直接放进 `jniLibs` 就能用。MNN 不是。

打开仓库看一眼：

```
apps/Android/MnnLlmChat/app/libs/          # 空
apps/Android/MnnLlmChat/app/src/main/jniLibs/   # 空
```

`build_prebuilt.gradle` 这名字看着像"预编译"，实际打开一看是**下载模型文件**的脚本，跟 `.so` 无关。

结论：**必须自己编**。

## 坑二：官方脚本是给 unix 写的

`project/android/build_64.sh` 里最后一行是 `make -j4`。Windows 上没有 `make`。

改成 CMake + Ninja：

```bash
cmake -G Ninja "$MNN_ROOT" \
  -DCMAKE_MAKE_PROGRAM="D:/as/SDK/cmake/3.30.5/bin/ninja.exe" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  ...
cmake --build . -j 8
```

有个小细节：`-DCMAKE_MAKE_PROGRAM` 必须显式给。Git Bash 的 `PATH` 里塞 Windows 风格路径，`command -v ninja` 是找不到的。

## 坑三：编译成功，但库是空的（最坑的一个）

第一次跑完：

```
[662/662] Linking CXX shared library libMNN.so
BUILD SUCCESSFUL
-rw-r--r-- 5.7M libMNN.so
```

零错误，零警告，5.7MB。

直到我拿 `nm` 检查符号：

```bash
llvm-nm -D --defined-only libMNN.so | grep createLLM
# （没有任何输出）
```

**一个 LLM 符号都没有。**

翻到根 `CMakeLists.txt` 第 78 行：

```cmake
option(MNN_BUILD_LLM "Build llm library based MNN." OFF)
```

默认 `OFF`。而官方的 `build_64.sh` **从头到尾没传这个开关**。

也就是说：照着官方脚本编，你得到的是一个纯 CV 推理库。要等链接自己的 JNI 时才会炸在 `undefined reference to MNN::Transformer::Llm::createLLM`，而那时你已经编了五分钟、看不出问题在哪。

修法：

```cmake
-DMNN_BUILD_LLM=ON
-DMNN_LLM_BUILD_DEMO=OFF          # 不编 demo，省时间
-DLLM_SUPPORT_HTTP_RESOURCE=OFF   # 不从 URL 拉模型，省体积
-DLLM_LOG_TO_STRING=ON            # 日志存字符串，便于上报
```

重编后：7.2MB，`grep -c "Transformer::Llm::"` = **59**。

## 坑四：网上教程还在教一个已被删除的参数

搜"MNN 编译 量化"，几乎所有教程（包括模型页 README）都让你加：

```cmake
-DMNN_CPU_WEIGHT_DEQUANT_GEMM=true
```

我加进去之后，CMake 给了这么一段：

```
CMake Warning:
  Manually-specified variables were not used by the project:
    MNN_CPU_WEIGHT_DEQUANT_GEMM
```

翻源码，`CMakeLists.txt:90`：

```cmake
# NOTE: MNN_CPU_WEIGHT_DEQUANT_GEMM has been permanently removed.
```

**永久移除。** 这条参数现在是纯噪音，传了只会收获一条警告。新版应该默认就开了，或者换了实现方式。

## 最终参数

```cmake
-DMNN_LOW_MEMORY=ON                  # 手机端降峰值内存
-DMNN_OPENCL=ON                      # GPU 加速（MNN 走 OpenCL，不是 Vulkan）
-DMNN_SEP_BUILD=OFF                  # 产出单个 libMNN.so
-DMNN_SUPPORT_TRANSFORMER_FUSE=ON    # LLM 依赖的算子融合
-DMNN_BUILD_LLM=ON                   # 关键，默认 OFF
-DMNN_LLM_BUILD_DEMO=OFF
-DLLM_SUPPORT_HTTP_RESOURCE=OFF
-DLLM_LOG_TO_STRING=ON
-DMNN_BUILD_DIFFUSION=OFF            # 不做图像生成
-DMNN_BUILD_OPENCV=OFF
-DMNN_IMGCODECS=OFF
-DMNN_BUILD_TEST=OFF
-DMNN_BUILD_BENCHMARK=OFF
```

## 一件容易漏的事

如果你的 App 要上 Android 15，链接时记得加：

```cmake
target_link_options(your_target PRIVATE "-Wl,-z,max-page-size=16384")
```

16KB page size 是 Android 15 的硬要求。不加这条，APK 可能装不上或者起来就崩，而且报错信息完全指不到这上面。

MnnLlmChat 自己的 `CMakeLists.txt` 里有这一行，但官方编译脚本那段没有。

## 数据汇总

| 项 | 数值 |
|---|---|
| 编译耗时（8 线程） | 4m55s（无 LLM）→ 3m39s（含 LLM）|
| `libMNN.so` | 5.7MB → **7.2MB** |
| LLM 符号数 | 0 → **59** |
| 自写 JNI 库 | 76KB |
| APK（debug，不含模型） | 24MB |

## 小结

四条坑的共同点是：**它们都不会在"编译"这一步暴露。**

- 没有预编译库 → 走到打包才发现
- 没有 make → 走到最后一行才发现
- 缺 `MNN_BUILD_LLM` → 走到链接才发现
- 已删除的参数 → 根本不报错，只是没生效

所以我后来的验收标准改成了："编译完必须 `nm` 检查符号，把输出贴出来"。`BUILD SUCCESSFUL` 什么都证明不了。
