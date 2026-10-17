package com.liteagent.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 与 assets/model-catalog.json 对应。sha256 来自 ModelScope 仓库 API 的真实返回值。 */
@Serializable
data class ModelCatalog(
    val version: Int = 1,
    val models: List<ModelEntry> = emptyList()
)

@Serializable
data class ModelEntry(
    val id: String,
    val repoId: String,
    val displayName: String,
    val quantization: String,
    val totalBytes: Long,
    val minMemoryMb: Long,
    val contextLength: Int,
    val files: List<ModelFile>
)

@Serializable
data class ModelFile(
    val name: String,
    val size: Long,
    val sha256: String
)

/**
 * 模型文件管理。
 *
 * 存放位置：`/sdcard/Android/data/<包名>/files/models/<id>/`
 * 为什么不用 assets：531MB 打进 APK，安装包直接废掉，而且每次更新模型都要发版。
 */
class ModelStore(private val context: Context) {

    /**
     * 模型根目录：**应用私有内部存储**。
     *
     * 为什么不用 getExternalFilesDir：
     * 实测在 DNAKE v911（Android 15 / Allwinner A537）上，用 adb push 到
     * `/sdcard/Android/data/<pkg>/files/models` 的文件，App 侧 `File.exists()` 返回 false——
     * shell 与 App 的挂载命名空间不一致，外部存储这条路在手动导入时不可靠。
     *
     * 而且这台设备的 `/data` 与 `/storage/emulated/0` 本来就是同一块 24G 分区，
     * 放内部存储不损失空间。副作用是文件管理器看不到，但模型本来也不该让用户手动翻。
     */
    val root: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun modelDir(id: String): File = File(root, id)

    fun configPath(id: String): String = File(modelDir(id), "config.json").absolutePath

    /** 完整性判定：7 个文件全部存在且字节数精确匹配 */
    fun isReady(entry: ModelEntry): Boolean = entry.files.all { f ->
        val file = File(modelDir(entry.id), f.name)
        val ok = file.isFile && file.length() == f.size
        if (!ok) {
            // 静默 false 是最难查的：界面只会显示"未下载"，看不出是缺文件、权限还是大小不符
            android.util.Log.w(
                "ModelStore",
                "not ready: ${file.absolutePath} exists=${file.exists()} " +
                    "len=${if (file.exists()) file.length() else -1L} expect=${f.size}"
            )
        }
        ok
    }

    fun downloadedBytes(entry: ModelEntry): Long = entry.files.sumOf { f ->
        val file = File(modelDir(entry.id), f.name)
        if (file.isFile) file.length().coerceAtMost(f.size) else 0L
    }

    /** 第一个缺失/不完整的文件，用于断点续传的起点 */
    fun firstIncomplete(entry: ModelEntry): ModelFile? = entry.files.firstOrNull { f ->
        File(modelDir(entry.id), f.name).let { !it.isFile || it.length() != f.size }
    }

    fun delete(id: String): Boolean = modelDir(id).deleteRecursively()

    fun hasEnoughSpace(bytes: Long): Boolean {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return base.usableSpace > bytes + 64L * 1024 * 1024
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun loadCatalog(context: Context): ModelCatalog = runCatching {
            context.assets.open("model-catalog.json").bufferedReader().use { it.readText() }
        }.map { json.decodeFromString<ModelCatalog>(it) }.getOrElse { ModelCatalog() }
    }
}
