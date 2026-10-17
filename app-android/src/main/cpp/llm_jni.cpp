// LiteAgent · MNN LLM JNI 封装
//
// 设计依据（全部来自 .refs/MNN-master 实际源码，不是猜的）：
//   - 创建/销毁：Llm::createLLM(configPath) / Llm::destroy(llm)   [llm.hpp:161-162]
//   - 配置加载：llm->set_config(json) → llm->load()                [llm.hpp:199/165]
//   - 流式输出：response(prompt, std::ostream*, end_with, max_new_tokens)  [llm.hpp:183]
//     MNN 没有回调式 API，流式只能通过 streambuf 接管输出。
//   - 可中断：先 response(msgs, os, "<eop>", 0) 只做 prefill，
//     再循环 generate(1) 逐 token 解码，两 token 之间可以检查停止标志。
//     这是 MnnLlmChat 的做法（llm_session.cpp:317-324），也是目前唯一能做到
//     "点停止立刻停"的路径。直接调 response(..., maxTokens) 会阻塞到结束。
//   - 性能数据：LlmContext 自带 load_us / prefill_us / decode_us / ttfa_us  [llm.hpp:119-150]

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <ostream>
#include <string>
#include <vector>

#include "llm/llm.hpp"

#define TAG "LiteAgentJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

namespace {

constexpr const char* kEndMarker = "<eop>";
constexpr int kEndMarkerLen = 5;

/**
 * 把 MNN 写到 ostream 的字节流，切成完整 UTF-8 字符后回调 Java。
 *
 * 为什么必须在这里切：一个中文字符占 3 字节，而 MNN 是按 token 写流的，
 * token 边界经常把一个汉字劈成两半。直接把半截字节丢给 Java，UI 上就是乱码方块。
 */
class JavaTokenSink : public std::streambuf {
public:
    JavaTokenSink(JNIEnv* env, jobject callback, jmethodID onToken, std::atomic<bool>* stop,
                  std::chrono::steady_clock::time_point start)
            : env_(env), callback_(callback), onToken_(onToken), stop_(stop), start_(start) {}

    bool finished() const { return finished_; }

    /** 首个完整字符推给 Java 的时刻（相对 start_）。0 表示一个字都没出 */
    int64_t firstTokenUs() const { return firstTokenUs_; }

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        consume(s, n);
        return n;
    }

    int_type overflow(int_type ch) override {
        if (ch == traits_type::eof()) return traits_type::not_eof(ch);
        char c = static_cast<char>(ch);
        consume(&c, 1);
        return ch;
    }

private:
    void consume(const char* s, std::streamsize n) {
        if (finished_ || stop_->load()) return;
        pending_.append(s, static_cast<size_t>(n));

        // 结束标记：命中就截断，不把标记本身吐给 UI。
        // 同时认 <eop>（MNN 的 end_with）和 <|im_end|>（Qwen 原生 eos），
        // 少认一个就会出现"回答完了还继续编"。
        static const char* const kMarkers[] = {"<eop>", "<|im_end|>", "</s>"};
        for (const char* marker : kMarkers) {
            const size_t pos = pending_.find(marker);
            if (pos != std::string::npos) {
                pending_.resize(pos);
                finished_ = true;
                break;
            }
        }

        size_t i = 0;
        std::string out;
        while (i < pending_.size()) {
            const int len = utf8Len(static_cast<unsigned char>(pending_[i]));
            if (len <= 0 || i + static_cast<size_t>(len) > pending_.size()) break;
            out.append(pending_, i, static_cast<size_t>(len));
            i += static_cast<size_t>(len);
        }
        // 结尾可能是 <eop> 的前缀（如 "<eo" 被切开），先留着等下个 chunk
        for (int k = kEndMarkerLen - 1; k >= 1; --k) {
            if (i >= static_cast<size_t>(k) &&
                pending_.compare(i - k, static_cast<size_t>(k), std::string(kEndMarker, k)) == 0) {
                i -= static_cast<size_t>(k);
                out.resize(out.size() - static_cast<size_t>(k));
                break;
            }
        }
        pending_.erase(0, i);

        if (out.empty()) return;

        // 首 token 延迟定义：从 prefill 开始到第一个字符交给 UI。
        // MNN 的纯文本路径不填 ttfa_us，只能自己量。
        if (firstTokenUs_ == 0) {
            firstTokenUs_ = std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - start_).count();
        }

        jstring js = env_->NewStringUTF(out.c_str());
        if (js == nullptr) {
            env_->ExceptionClear();
            return;
        }
        const jboolean wantStop = env_->CallBooleanMethod(callback_, onToken_, js);
        env_->DeleteLocalRef(js);
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            stop_->store(true);
            return;
        }
        // Java 侧返回 true 表示用户点了停止
        if (wantStop) stop_->store(true);
    }

    static int utf8Len(unsigned char b) {
        if ((b & 0x80) == 0) return 1;
        if ((b & 0xE0) == 0xC0) return 2;
        if ((b & 0xF0) == 0xE0) return 3;
        if ((b & 0xF8) == 0xF0) return 4;
        return 0;
    }

    JNIEnv* env_;
    jobject callback_;
    jmethodID onToken_;
    std::atomic<bool>* stop_;
    std::string pending_;
    bool finished_ = false;
    std::chrono::steady_clock::time_point start_;
    int64_t firstTokenUs_ = 0;
};

struct Session {
    Llm* llm = nullptr;
    std::atomic<bool> stop{false};
    std::string configPath;
    int64_t loadUs = 0;
    // MNN 只在 omni.cpp（多模态）里填 ttfa_us / decode_us，
    // 纯文本路径只把它们清零。所以这两个值必须自己量，否则跑分页永远显示 0。
    int64_t ttfaUs = 0;
    int64_t decodeUs = 0;
};

Session* asSession(jlong handle) {
    return reinterpret_cast<Session*>(handle);
}

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

void throwIllegalState(JNIEnv* env, const char* msg) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) env->ThrowNew(clazz, msg);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_liteagent_app_engine_LlmEngine_nativeCreate(JNIEnv* env, jobject /*thiz*/,
                                                     jstring configPath, jstring configJson) {
    const std::string path = jstr(env, configPath);
    if (path.empty()) {
        throwIllegalState(env, "模型 config 路径为空");
        return 0;
    }

    const auto t0 = std::chrono::steady_clock::now();
    Llm* llm = Llm::createLLM(path);
    if (llm == nullptr) {
        throwIllegalState(env, ("createLLM 失败: " + path).c_str());
        return 0;
    }

    const std::string cfg = jstr(env, configJson);
    if (!cfg.empty()) {
        llm->set_config(cfg);
    }

    if (!llm->load()) {
        Llm::destroy(llm);
        throwIllegalState(env, "模型加载失败（文件缺失、损坏或内存不足）");
        return 0;
    }

    const auto t1 = std::chrono::steady_clock::now();
    auto* session = new Session();
    session->llm = llm;
    session->configPath = path;
    session->loadUs = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
    LOGI("模型加载完成, 耗时 %lld ms", static_cast<long long>(session->loadUs / 1000));
    return reinterpret_cast<jlong>(session);
}

/**
 * 流式生成。
 * @return 实际生成的 token 数；-1 表示 handle 无效
 * 回调接口：boolean onToken(String text)，返回 true 表示请求停止。
 */
JNIEXPORT jint JNICALL
Java_com_liteagent_app_engine_LlmEngine_nativeGenerate(JNIEnv* env, jobject /*thiz*/,
                                                       jlong handle, jstring prompt,
                                                       jstring systemPrompt, jint maxTokens,
                                                       jobject callback) {
    Session* s = asSession(handle);
    if (s == nullptr || s->llm == nullptr) return -1;
    if (callback == nullptr) return -2;

    s->stop.store(false);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cbClass);
    if (onToken == nullptr) {
        env->ExceptionClear();
        LOGE("回调缺少 onToken(String):Boolean");
        return -2;
    }

    // 计时起点放在 prefill 之前：首 token 延迟应该是"用户点发送到看见第一个字"
    const auto tStart = std::chrono::steady_clock::now();
    JavaTokenSink sink(env, callback, onToken, &s->stop, tStart);
    std::ostream os(&sink);

    ChatMessages messages;
    const std::string system = jstr(env, systemPrompt);
    if (!system.empty()) messages.emplace_back("system", system);
    messages.emplace_back("user", jstr(env, prompt));

    // 第一步：只做 prefill（max_new_tokens = 0），让 KV cache 就绪
    s->llm->response(messages, &os, kEndMarker, 0);

    // 第二步：逐 token 解码，每个 token 之间都有机会退出
    int generated = 0;
    s->decodeUs = 0;
    while (!s->stop.load() && !sink.finished() && generated < maxTokens) {
        const auto tStep = std::chrono::steady_clock::now();
        s->llm->generate(1);
        s->decodeUs += std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - tStep).count();
        ++generated;

        // 关键：单步解码时引擎会在每个 token 之后把状态标成 FINISHED。
        // 这是 stepping 模式的已知行为（MnnLlmChat 里有同样的
        // restoreAndroidSteppingStatusIfNeeded 逻辑，用 const_cast 改回去）。
        // 不处理的话循环在第一个 token 后就 break——真机表现是气泡里一个字都没有。
        auto* ctx = const_cast<MNN::Transformer::LlmContext*>(s->llm->getContext());
        if (ctx->status == LlmStatus::INTERNAL_ERROR || ctx->status == LlmStatus::TIMEOUT) {
            break;
        }
        if (ctx->status == LlmStatus::MAX_TOKENS_FINISHED ||
            ctx->status == LlmStatus::NORMAL_FINISHED) {
            ctx->status = LlmStatus::RUNNING;
        }
    }
    s->ttfaUs = sink.firstTokenUs();
    os.flush();
    return generated;
}

JNIEXPORT void JNICALL
Java_com_liteagent_app_engine_LlmEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Session* s = asSession(handle);
    if (s != nullptr) s->stop.store(true);
}

/** 返回 [loadUs, prefillUs, decodeUs, ttfaUs, promptTokens, genTokens] */
JNIEXPORT jlongArray JNICALL
Java_com_liteagent_app_engine_LlmEngine_nativePerf(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Session* s = asSession(handle);
    jlong values[6] = {0, 0, 0, 0, 0, 0};
    if (s != nullptr && s->llm != nullptr) {
        const auto* ctx = s->llm->getContext();
        values[0] = s->loadUs;
        values[1] = ctx->prefill_us;
        // 引擎填了就用引擎的；没填（纯文本路径永远是 0）用自己量的
        values[2] = ctx->decode_us > 0 ? ctx->decode_us : s->decodeUs;
        values[3] = ctx->ttfa_us > 0 ? ctx->ttfa_us : s->ttfaUs;
        values[4] = ctx->prompt_len;
        values[5] = ctx->gen_seq_len;
    }
    jlongArray arr = env->NewLongArray(6);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, 6, values);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_liteagent_app_engine_LlmEngine_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Session* s = asSession(handle);
    if (s == nullptr) return;
    s->stop.store(true);
    if (s->llm != nullptr) {
        Llm::destroy(s->llm);
        s->llm = nullptr;
    }
    delete s;
}

/** 清理当前会话的 KV cache / 历史，用于开始新对话 */
JNIEXPORT void JNICALL
Java_com_liteagent_app_engine_LlmEngine_nativeReset(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Session* s = asSession(handle);
    if (s != nullptr && s->llm != nullptr) s->llm->reset();
}

} // extern "C"
