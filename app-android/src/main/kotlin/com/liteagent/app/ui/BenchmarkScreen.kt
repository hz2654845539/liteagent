package com.liteagent.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale

/**
 * 跑分界面（顶部 Tab 第二个）。
 *
 * 【它是干什么的】
 * 给一个「可量化」的地方：选定模型后，跑一轮标准基准
 * （固定 prompt、固定 maxTokens），把加载耗时 / 首 token 延迟 /
 * prefill 与 decode 速度 / 内存峰值展示出来，可一键复制成 JSON。
 *
 * 【为什么单独一个页】
 * 性能数据要和日常聊天分开——聊天页的速度受 RAG 检索、历史长度影响，
 * 是「真实体验口径」；这里是「纯净口径」，两者数据不能混着比
 * （README 的真机表里专门标注了口径差异）。
 *
 * 注意：跑分前必须先在对话页加载好模型；极限档设备跑 1.5B 会明显偏慢，
 * 这是预期行为不是 bug（见 docs/11 的 1.5B 压力测试数据）。
 */
@Composable
fun BenchmarkScreen(vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("性能跑分", style = MaterialTheme.typography.headlineSmall)
        Text(
            "固定三条 prompt（短/中/长），指标全部取自 MNN 的 LlmContext。" +
                "换 prompt 等于换尺子，所以这里不允许自定义。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(
                onClick = vm::runBenchmark,
                enabled = state.engineReady && !state.benchmarking
            ) {
                Text(if (state.benchmarking) "跑分中…" else "开始跑分")
            }
            Spacer(Modifier.width(12.dp))
            if (!state.engineReady) {
                Text("先加载模型", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.benchmarking) {
            val total = state.benchmarkTotal.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { state.benchmarkProgress.toFloat() / total },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "第 ${state.benchmarkProgress + 1} / $total 条",
                style = MaterialTheme.typography.labelSmall
            )
        }

        state.benchmark?.let { report ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${report.modelId}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "加载 ${report.loadMs} ms · 内存峰值 ${report.peakMemMb} MB · " +
                            "${report.threads} 线程 · GPU ${if (report.useGpu) "开" else "关"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Column {
                            TableRow(
                                listOf("Prompt", "输入", "输出", "首token", "prefill", "decode"),
                                header = true
                            )
                            report.runs.forEach { run ->
                                TableRow(
                                    listOf(
                                        run.promptName,
                                        run.promptTokens.toString(),
                                        run.generatedTokens.toString(),
                                        "${run.ttftMs}ms",
                                        "${fmt(run.prefillTps)} t/s",
                                        "${fmt(run.decodeTps)} t/s"
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val text = report.toMarkdown()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("benchmark", text))
                    Toast.makeText(context, "已复制 Markdown", Toast.LENGTH_SHORT).show()
                }) { Text("复制 Markdown") }

                OutlinedButton(onClick = {
                    val dir = File(context.getExternalFilesDir(null), "benchmark")
                    dir.mkdirs()
                    val file = File(dir, "perf-${report.timestamp}.json")
                    file.writeText(report.toJson())
                    Toast.makeText(context, "已保存 ${file.name}", Toast.LENGTH_SHORT).show()
                }) { Text("导出 JSON") }
            }
        }

        Text(
            "怎么读这组数字：\n" +
                "· prefill 决定长文档输入的等待时间，RAG 场景比 decode 更重要；\n" +
                "· decode 决定用户感受到的打字速度；\n" +
                "· 首 token 延迟 = prefill + 首个 decode，是体感最直接的指标；\n" +
                "· 内存峰值超过设备可用内存的 60%，就有被系统杀掉的风险。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        cells.forEachIndexed { i, cell ->
            Text(
                text = cell,
                modifier = Modifier.width(if (i == 0) 64.dp else 78.dp),
                style = if (header) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.bodySmall
                },
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)
