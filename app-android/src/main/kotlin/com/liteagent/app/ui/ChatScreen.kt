package com.liteagent.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liteagent.app.capability.Recommendation
import com.liteagent.app.capability.modelOrNull
import com.liteagent.app.data.PresetStore
import com.liteagent.shared.ChatMessage
import com.liteagent.shared.ChatRole
import java.util.Locale

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            // 用 scrollToItem 而不是 animate：这里每个 token 都会触发一次，
            // 动画会和下一个 token 的滚动抢进度，长回答时看起来是"追不上"
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        val context = LocalContext.current
        ChatTopBar(
            state = state,
            onPreset = vm::setPreset,
            onSession = vm::switchSession,
            onNew = vm::newSession,
            onExport = {
                val markdown = vm.exportCurrent()
                if (markdown.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, markdown)
                        putExtra(Intent.EXTRA_SUBJECT, "LiteAgent 对话")
                    }
                    context.startActivity(Intent.createChooser(intent, "导出对话"))
                }
            },
            onDelete = vm::deleteSession
        )

        StatusCard(
            state = state,
            onDownload = vm::downloadModel,
            onLoad = vm::loadModel,
            onToggle = vm::toggleDevicePanel,
            onSelectModel = vm::selectModel,
            onRetrievalOnly = vm::toggleRetrievalOnly
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message, generating = state.generating)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        state.perf?.let { perf ->
            Text(
                text = "首 token ${perf.ttftMs} ms · " +
                    "${String.format(Locale.US, "%.1f", perf.decodeTps)} tok/s · " +
                    "prefill ${String.format(Locale.US, "%.1f", perf.prefillTps)} tok/s · " +
                    "加载 ${perf.loadMs} ms",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // 附带图片时的提示条：告诉用户这次不查文档库，并给一个改回去的入口
        if (state.imageAttached) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已附图片 · 本次不检索文档",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "改为检索文档",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { vm.toggleImageOnly() }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选图 → OCR → 回填输入框。填而不发，是因为 OCR 会认错字，让用户看一眼再发
            val pickImage = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri -> uri?.let { vm.importImage(it) } }

            IconButton(
                onClick = { pickImage.launch("image/*") },
                enabled = !state.ocrRunning && !state.generating
            ) {
                if (state.ocrRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "从图片识别文字",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            OutlinedTextField(
                value = state.draft,
                onValueChange = vm::setDraft,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        when {
                            state.ocrRunning -> "正在识别图片…"
                            state.retrievalOnly -> "问文档里的内容（仅检索）"
                            state.engineReady -> "问点什么，或点左边选张图"
                            else -> "先加载模型"
                        }
                    )
                },
                maxLines = 4,
                enabled = (state.engineReady || state.retrievalOnly) &&
                    !state.generating && !state.ocrRunning,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (state.draft.isNotBlank()) {
                        vm.send(state.draft)
                        vm.setDraft("")
                    }
                })
            )
            Spacer(Modifier.width(8.dp))
            if (state.generating) {
                Button(onClick = vm::stop) { Text("停止") }
            } else {
                Button(
                    onClick = {
                        vm.send(state.draft)
                        vm.setDraft("")
                    },
                    enabled = (state.engineReady || state.retrievalOnly) && state.draft.isNotBlank()
                ) { Text("发送") }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    onPreset: (String) -> Unit,
    onSession: (String) -> Unit,
    onNew: () -> Unit,
    onExport: () -> Unit,
    onDelete: (String) -> Unit
) {
    var presetMenu by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    val preset = PresetStore.find(state.presetId)
    val currentTitle = state.sessions.firstOrNull { it.id == state.currentSessionId }?.title
        ?: "当前对话"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = { presetMenu = true }) {
                Text("${preset.emoji} ${preset.name}")
            }
            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                PresetStore.builtin.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Text("${p.emoji} ${p.name}" + if (p.id == preset.id) "（当前）" else "")
                        },
                        onClick = {
                            presetMenu = false
                            onPreset(p.id)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Box {
            TextButton(onClick = { sessionMenu = true }) {
                Text(
                    if (currentTitle.length > 8) currentTitle.take(8) + "…" else currentTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
                if (state.sessions.isEmpty()) {
                    DropdownMenuItem(text = { Text("还没有历史对话") }, onClick = { sessionMenu = false })
                }
                state.sessions.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text("${s.title.take(14)} · ${s.messageCount} 条")
                        },
                        onClick = {
                            sessionMenu = false
                            onSession(s.id)
                        },
                        trailingIcon = {
                            // 删除放在菜单项右侧而不是长按：会话不多，明确入口更安全
                            IconButton(
                                onClick = {
                                    sessionMenu = false
                                    onDelete(s.id)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "删除会话",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        TextButton(onClick = onNew) { Text("新对话") }
        TextButton(onClick = onExport) { Text("导出") }
    }
}

@Composable
private fun StatusCard(
    state: ChatUiState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onToggle: () -> Unit,
    onSelectModel: (String) -> Unit,
    onRetrievalOnly: (Boolean) -> Unit
) {
    val rec = state.recommendation
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("LiteAgent", style = MaterialTheme.typography.titleMedium)
            Text(
                "端侧离线 AI 助理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            val model = rec.modelOrNull()
            when {
                rec == null -> Text("探测中…", style = MaterialTheme.typography.bodySmall)

                model == null -> Text(
                    (rec as? Recommendation.Unsupported)?.message ?: "无法给出推荐",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                else -> {
                    val isExtreme = rec is Recommendation.Restricted
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${if (isExtreme) "极限模式 " else "推荐 "}" +
                                "${state.modelName ?: model.modelId} · ${model.quantization} · " +
                                "${model.threads} 线程 · ${model.contextLength} 上下文",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isExtreme) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                        // 推荐是默认值不是强制值：想省空间就用小的，想试大的也不用改代码
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("切换") }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                state.availableModels.forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${m.displayName} · ${m.totalBytes / (1024 * 1024)}MB" +
                                                    if (m.id == state.modelId) "（当前）" else ""
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            onSelectModel(m.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        model.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isExtreme) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.loading -> Text("模型加载中…", style = MaterialTheme.typography.bodySmall)
                    state.engineReady -> Text("模型已就绪", style = MaterialTheme.typography.bodySmall)
                    state.downloading -> {
                        LinearProgressIndicator(
                            progress = { state.downloadPercent / 100f },
                            modifier = Modifier.width(120.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${state.downloadPercent}%", style = MaterialTheme.typography.bodySmall)
                    }
                    state.modelDownloaded -> TextButton(onClick = onLoad) { Text("加载模型") }
                    else -> TextButton(onClick = onDownload) {
                        Text("下载模型 ${state.modelSizeMb}MB")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggle) {
                    Text(if (state.showDevicePanel) "收起设备" else "设备信息")
                }
            }

            // 模型跑不动时的兜底：不调模型，直接把命中的原文段落给用户。
            // 只在"模型已下载但没加载"时出现——那时用户最需要这个开关。
            if (!state.engineReady && state.modelDownloaded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("仅检索模式", style = MaterialTheme.typography.labelSmall)
                    Switch(
                        checked = state.retrievalOnly,
                        onCheckedChange = onRetrievalOnly,
                        modifier = Modifier.scale(0.75f)
                    )
                    Text(
                        "不调模型，直接返回原文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.showDevicePanel) {
                val p = state.profile
                if (p != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${p.socModel ?: p.hardware} · ${p.totalMemMb / 1024}GB · " +
                            "API ${p.sdkInt} · ${p.gpuRenderer ?: "GPU 未知"} · GLES ${p.glesVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (p.failures.isNotEmpty()) {
                        Text(
                            "兜底字段: ${p.failures.joinToString()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, generating: Boolean) {
    val context = LocalContext.current
    val isUser = message.role == ChatRole.USER
    val bg = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .background(bg, RoundedCornerShape(12.dp))
                // 长按复制：AI 的回答经常需要贴到别处，比每屏放一个复制按钮干净
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (message.content.isNotBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            cm?.setPrimaryClip(
                                ClipData.newPlainText("message", message.content)
                            )
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val text = if (!isUser && message.content.isEmpty() && generating) "…" else message.content
            Text(text, style = MaterialTheme.typography.bodyMedium)

            // RAG 的可见证据：回答带来源，不是凭空说的
            if (message.citations.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "来源：" + message.citations.joinToString("  ") { "《${it.docName}》" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
