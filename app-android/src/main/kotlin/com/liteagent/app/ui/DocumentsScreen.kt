package com.liteagent.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liteagent.app.data.DocMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 资料库。这是产品的主入口——聊天框谁都有，能装自己的文件才是差异。
 *
 * 用 SAF（OpenDocument）而不是申请存储权限：Android 10+ 的分区存储下，
 * 申请 READ_EXTERNAL_STORAGE 既拿不到全部文件，又会让用户警惕。SAF 只给用户选中的那一个文件。
 */
@Composable
fun DocumentsScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.importDocument(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("资料库", style = MaterialTheme.typography.headlineSmall)
        Text(
            "导入的文档只存在本机，不会上传。提问时会自动检索相关片段，并标注来源。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { picker.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "*/*")) },
                enabled = !state.importing
            ) {
                Text(if (state.importing) "导入中…" else "添加文档")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "支持 txt / md · 共 ${state.documents.size} 篇",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.importing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        if (state.documents.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "还没有文档。\n把手机里的方案、笔记导进来，再问「我上次方案里的量化精度是多少」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.documents, key = { it.id }) { doc ->
                    DocRow(doc) { vm.deleteDocument(doc.id) }
                }
            }
        }
    }
}

@Composable
private fun DocRow(doc: DocMeta, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    doc.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${doc.chunkCount} 个片段 · ${formatSize(doc.sizeBytes)} · " +
                        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(doc.importedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
