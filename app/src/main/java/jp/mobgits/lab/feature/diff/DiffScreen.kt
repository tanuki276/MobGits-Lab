package jp.mobgits.lab.feature.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import jp.mobgits.lab.*
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.*
import kotlinx.coroutines.*

/** The API page size is one file; parsing is off the main thread and rows are lazy. */
@Composable fun DiffScreen(vm: AppViewModel) {
    val state = vm.state
    val file = state.entries.firstOrNull()
    var lines by remember { mutableStateOf<List<DiffLine>>(emptyList()) }
    var position by remember { mutableStateOf<ReviewPosition?>(null) }
    var parsing by remember { mutableStateOf(false) }
    LaunchedEffect(file?.text("new_path"), file?.text("diff")) {
        parsing = true
        lines = withContext(Dispatchers.Default) { UnifiedDiff.parse(file?.text("diff").orEmpty()) }
        parsing = false
    }
    position?.let { pos -> AlertDialog(onDismissRequest = { if (!state.submitting) position = null },
        title = { Text("${pos.newPath} · 旧${pos.oldLine ?: "–"} / 新${pos.newLine ?: "–"}") },
        text = { CommentEditor(vm, position = pos) { position = null } },
        confirmButton = { TextButton(enabled = !state.submitting, onClick = { position = null }) { Text("閉じる") } }) }
    Column {
        Text("ファイル ${state.page} · 旧 / 新 行番号", Modifier.padding(12.dp))
        Row {
            OutlinedButton(onClick = vm::previousDiff, enabled = state.page > 1 && !state.loading) { Text("前のファイル") }
            OutlinedButton(onClick = vm::more, enabled = state.next != null && !state.loading) { Text("次のファイル") }
        }
        if (parsing) LinearProgressIndicator(Modifier.fillMaxWidth())
        val unavailable = file == null || file.optBoolean("too_large") || file.optBoolean("collapsed") || file.text("diff").isBlank()
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text(file?.text("new_path").orEmpty(), Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
            if (file?.optBoolean("renamed_file") == true) item { Text("名前変更: ${file.text("old_path")} → ${file.text("new_path")}") }
            if (unavailable) item { EmptyMessage(when {
                file?.optBoolean("too_large") == true -> "差分サイズがGitLabの上限を超えています。GitLabで確認してください。"
                file?.optBoolean("collapsed") == true -> "GitLabがこの差分を省略しました。GitLabで確認してください。"
                else -> "表示できるテキスト差分がありません（バイナリ・名前変更のみなど）。"
            }) }
            else itemsIndexed(lines) { _, line ->
                val bg = when (line.kind) { DiffKind.ADDED -> Color(0xFFDCFCE7); DiffKind.REMOVED -> Color(0xFFFEE2E2); DiffKind.HUNK -> Color(0xFFDBEAFE); else -> MaterialTheme.colorScheme.surface }
                val fg = if (line.kind in setOf(DiffKind.ADDED, DiffKind.REMOVED, DiffKind.HUNK)) Color(0xFF17202A) else MaterialTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth().background(bg).clickable(enabled = line.commentable && vm.refs().complete && !state.offline && !state.loading,
                    onClickLabel = "この行にコメント") {
                    position = ReviewPosition(vm.refs(), file!!.text("old_path"), file.text("new_path"), line.oldLine, line.newLine)
                }.semantics { contentDescription = "旧行${line.oldLine ?: "なし"} 新行${line.newLine ?: "なし"} ${line.text}" }.padding(8.dp)) {
                    Text("${line.oldLine ?: "–"}/${line.newLine ?: "–"}", Modifier.width(80.dp), fontFamily = FontFamily.Monospace, color = fg)
                    Text(line.text, Modifier.weight(1f).horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, color = fg)
                }
            }
        }
    }
}
