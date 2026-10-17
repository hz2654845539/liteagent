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
                    if ((!autoPrompt.isNullOrBlank() || !autoModel.isNullOrBlank()) &&
                        savedInstanceState == null
                    ) {
                        LaunchedEffect(autoPrompt, autoModel) {
                            // 先切模型（如果指定了），等它落定再加载
                            if (!autoModel.isNullOrBlank()) {
                                vm.selectModel(autoModel)
                                withTimeoutOrNull(20_000) {
                                    while (vm.state.value.modelId != autoModel) delay(200)
                                }
                            }
                            if (!vm.state.value.engineReady) vm.loadModel()
                            if (!autoPrompt.isNullOrBlank()) {
                                withTimeoutOrNull(90_000) {
                                    while (!vm.state.value.engineReady) delay(500)
                                    vm.send(autoPrompt)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** adb 自测用：am start ... --es autotest "你好" --es automodel "Qwen2.5-1.5B-Instruct-MNN" */
        const val EXTRA_AUTOTEST = "autotest"
        const val EXTRA_AUTOMODEL = "automodel"
    }
}
