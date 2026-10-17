package com.liteagent.app.vision

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 图片 → 文字。
 *
 * 为什么是 OCR 而不是视觉大模型：
 * 端侧视觉模型最小的是 Qwen2-VL-2B 这一档，权重 1.5GB 起，这台 2GB 设备连 0.5B
 * 都要吃掉 688MB，不可能跑得动 VLM。而 OCR 只需要十几 MB，能立刻把
 * "拍一张文档 → 问里面的内容"这条链路跑通——这恰好也是 RAG 最自然的输入方式。
 *
 * 选 ML Kit 中文识别而不是 Tesseract：中文竖排、混排的准确率差一个档次，
 * 而且 Tesseract 要自己带训练数据（同样十几 MB）且集成更麻烦。
 *
 * 没选 bundled 之外的方案时唯一的风险是依赖 Google Play 服务。
 * 这一版用的是 `com.google.mlkit:text-recognition-chinese`（模型打在 APK 里），
 * 实测 APK 从 8.4MB 涨到 22MB，说明模型确实进包了，不需要运行时下载。
 */
class ImageTextExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun extract(context: Context, uri: Uri): Result<String> =
        suspendCancellableCoroutine { cont ->
            val image = try {
                InputImage.fromFilePath(context, uri)
            } catch (e: Exception) {
                cont.resume(Result.failure(e))
                return@suspendCancellableCoroutine
            }

            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    cont.resume(
                        if (text.isBlank()) {
                            Result.failure(IllegalStateException("图里没识别出文字，换个角度或调亮一点再试"))
                        } else {
                            Result.success(text)
                        }
                    )
                }
                .addOnFailureListener { e ->
                    cont.resume(Result.failure(e))
                }
        }
}
