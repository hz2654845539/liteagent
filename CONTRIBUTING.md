# 贡献指南

这个项目的主要目的是验证"端侧 AI 能不能做成产品"，所以欢迎的不只是代码，还有**实测数据**——尤其是不同机型上的跑分。

## 跑起来

```bash
# 1. 编译 MNN（只需一次，约 4 分钟）
bash tools/build_mnn_android.sh

# 2. 构建 APK
./gradlew :app-android:assembleDebug

# 3. 装到 arm64 真机（模拟器不行：MNN 只有 arm64 的 .so）
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
```

模型在 App 内下载（按设备推荐自动选 0.5B / 1.5B / 3B），也可以手动：

```bash
bash tools/download_model.sh
adb push models/Qwen2.5-0.5B-Instruct-MNN /sdcard/Android/data/com.liteagent.app.debug/files/models/
```

## 提交前

```bash
./gradlew :app-android:testDebugUnitTest              # 42 个
cd backend && python -m unittest discover -s tests    # 13 个
```

两条都要绿。新增逻辑必须有对应用例——这个项目里最容易出错的不是"写错"，是"看起来对"。

## 代码规范

- 注释解释**为什么**，不是做了什么。特别是"这里为什么不能那样写"
- 分层不能破：`engine/` 和 `capability/` 不依赖 `ui/`
- 主线程不做 IO、不做阻塞调用（JNI 的 `load()` 会跑几秒）
- 新增依赖前先说明体积代价，写在 PR 描述里

## 数据类 PR 特别欢迎

不用改代码，只需要：

1. 在设备上打开 App 的「跑分」页，跑一轮
2. 点「复制 Markdown」
3. 连同机型、内存、Android 版本一起贴到 PR 里

这些数据会进 README 的设备适配表。目前只有一台 2GB 工控机的数据（它跑不了模型，用来验证"不支持"分支）。

## 不做什么

账号体系、云同步、无障碍 UI 自动化、图像生成、语音唤醒。这些不是做不了，是做不完——把核心链路做扎实比铺开六个半成品有价值。

范围之外的想法欢迎开 issue 讨论，但别直接提 PR。
