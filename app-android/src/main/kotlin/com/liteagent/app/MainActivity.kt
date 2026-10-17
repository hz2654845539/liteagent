package com.liteagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liteagent.app.ui.BenchmarkScreen
import com.liteagent.app.ui.ChatScreen
import com.liteagent.app.ui.ChatViewModel
import com.liteagent.app.ui.DocumentsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 跟随系统深浅色。端侧 App 常被长时间开着，亮色在夜间很刺眼
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 两个 Tab 共用一个 ViewModel：LlmEngine 是独占资源，
                    // 拆成两个 VM 会出现两个引擎实例同时加载模型，直接把内存打爆
                    val vm: ChatViewModel = viewModel()
                    var tab by remember { mutableIntStateOf(0) }

                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                text = { Text("对话") }
                            )
                            Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                text = { Text("跑分") }
                            )
                            Tab(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                text = { Text("资料库") }
                            )
                        }
                        when (tab) {
                            0 -> ChatScreen(vm)
                            1 -> BenchmarkScreen(vm)
                            else -> DocumentsScreen(vm)
                        }
                    }

                    // 开发自测入口：adb shell am start ... --es autotest "hi"
                    // 这台工控设备的触摸屏拿不到输入法连接，没法用 adb input text 验证生成链路，
                    // 所以留一个跳过 UI 输入的入口。正式包不会带这个 extra。
                    val autoPrompt = intent?.getStringExtra(EXTRA_AUTOTEST)
                    val autoModel = intent?.getStringExtra(EXTRA_AUTOMODEL)
                    val autoBench = intent?.getStringExtra(EXTRA_AUTOBENCH)
                    val autoOcr = intent?.getStringExtra(EXTRA_AUTOOCR)
                    val threadsOverride = intent?.getIntExtra(EXTRA_THREADS, 0) ?: 0
                    val hasAuto = !autoPrompt.isNullOrBlank() || !autoModel.isNullOrBlank() ||
                        autoBench == "1" || !autoOcr.isNullOrBlank()
                    if (hasAuto && savedInstanceState == null) {
                        LaunchedEffect(autoPrompt, autoModel, autoBench, autoOcr, threadsOverride) {
                            if (!autoModel.isNullOrBlank()) {
                                vm.selectModel(autoModel)
                                withTimeoutOrNull(20_000) {
                                    while (vm.state.value.modelId != autoModel) delay(200)
                                }
                            }
                            // OCR 先跑：它不依赖模型，纯 ML Kit
                            if (!autoOcr.isNullOrBlank()) {
                                vm.importImage(android.net.Uri.fromFile(java.io.File(autoOcr)))
                                withTimeoutOrNull(40_000) {
                                    while (vm.state.value.draft.isBlank() &&
                                        vm.state.value.error == null
                                    ) {
                                        delay(300)
                                    }
                                }
                            }
                            if (!vm.state.value.engineReady && autoBench == "1") {
                                vm.loadModel(threadsOverride.takeIf { it > 0 })
                            }
                            if (!vm.state.value.engineReady && !autoPrompt.isNullOrBlank()) {
                                vm.loadModel(threadsOverride.takeIf { it > 0 })
                            }
                            withTimeoutOrNull(120_000) {
                                while (!vm.state.value.engineReady &&
                                    !autoPrompt.isNullOrBlank()
                                ) {
                                    delay(300)
                                }
                            }
                            when {
                                autoBench == "1" -> {
                                    vm.runBenchmark()
                                    withTimeoutOrNull(600_000) {
                                        while (vm.state.value.benchmark == null) delay(1000)
                                    }
                                }
                                !autoPrompt.isNullOrBlank() -> {
                                    // 有 OCR 结果时，问题必须拼在后面。
                                    // 只发 OCR 原文的话，模型收到一堆没有问题的文字，会开始编。
                                    val ocrText = vm.state.value.draft
                                    val prompt = when {
                                        ocrText.isBlank() -> autoPrompt
                                        else -> "$ocrText\n\n$autoPrompt"
                                    }
                                    vm.send(prompt)
                                    vm.setDraft("")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * adb 自测入口：
         *   --es autotest "你好"                    发一条消息
         *   --es automodel "Qwen2.5-1.5B-Instruct-MNN"  指定模型
         *   --es autobench "1"                      跑一轮 benchmark 并落盘
         *   --ei threads 4                          覆盖线程数（调优实验用）
         */
        const val EXTRA_AUTOTEST = "autotest"
        const val EXTRA_AUTOMODEL = "automodel"
        const val EXTRA_AUTOBENCH = "autobench"
        const val EXTRA_THREADS = "threads"
        /** 直接从文件路径跑 OCR，绕过文件选择器（SAF 没法自动化） */
        const val EXTRA_AUTOOCR = "autoocr"
    }
}
