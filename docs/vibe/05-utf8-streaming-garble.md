# 案例 5 · 中文流式输出变成方块

> 时间：2026-10-17 · 阶段：流式渲染

## 需求

MNN 逐个 token 写进 `std::ostream`，要实时显示到 Compose 上，中文不能乱码。

## 我给的 prompt

```
在 streambuf 的 xsputn 里把字节转成 jstring 回调 Kotlin，实现流式显示。
```

## AI 首版输出

```cpp
std::streamsize xsputn(const char* s, std::streamsize n) override {
    jstring js = env->NewStringUTF(std::string(s, n).c_str());
    env->CallVoidMethod(callback, onToken, js);
    return n;
}
```

三行，直觉上完全正确：来多少字节就转多少字符串。

## 问题

中文一个字 3 个字节（UTF-8），而 MNN 是**按 token 写流**的，token 边界和字符边界不重合。

于是经常出现：这次写进来 2 个字节（一个汉字的前 2/3），下次写进来 1 个字节（剩下的 1/3）。两次都拿去 `NewStringUTF`，每次都是非法 UTF-8 序列，屏幕上就是两个方块。

这个问题在纯英文下永远不会出现，在中文下**必然出现**，而且不同模型的分词表切法不同，出现频率还不一样。

## 修正 prompt

```
按 UTF-8 首字节判断字符长度，凑够一个完整字符才回调；
不足的部分留在缓冲区等下一次。
参考 MNN 自己的 utf8_stream_processor.hpp。
```

## 最终方案

```cpp
void consume(const char* s, size_t n) {
    pending_.append(s, n);
    size_t i = 0;
    std::string out;
    while (i < pending_.size()) {
        int len = utf8Len(pending_[i]);          // 首字节决定 1/2/3/4
        if (len <= 0 || i + len > pending_.size()) break;   // 不完整，等下次
        out.append(pending_, i, len);
        i += len;
    }
    pending_.erase(0, i);
    if (!out.empty()) emit(out);                 // 一次 JNI 调用送一整批完整字符
}
```

顺带解决了另一个问题：批处理之后，一个 token 只触发一次 JNI 调用，而不是每个字节一次。

## 这个案例说明什么

AI 给的代码**在它想象的语言环境里是对的**——它默认"每次写入是一个完整的字符串"。这个假设在英文里成立，在中文里不成立。

端侧中文场景有一批这样的隐性假设：字符边界、分词、标点、全角半角。它们不会让编译失败，只会让产品在中文用户手里显得很粗糙。
