package jp.mobgits.lab.feature.repository

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.mobgits.lab.AppViewModel
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.Destination

@Composable
fun RepositoryScreen(app: AppViewModel) {
    val account = app.state.directory.activeAccount?.id ?: return
    val d = app.state.route
    val context = LocalContext.current.applicationContext
    val vm: RepositoryViewModel = viewModel(key = "repo:$account:${d.project}", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = RepositoryViewModel(
            RepositoryUseCase(app.graph.resources), WorkspaceStore(context, account, d.project), account, d.project, d.ref) as T
    })
    RepositoryContent(vm) { app.navigate(Destination("mr", d.project, it), true) }
}

@Composable
internal fun RepositoryContent(vm: RepositoryViewModel, openMr: (Long) -> Unit) {
    var editing by rememberSaveable(vm.source?.path) { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var newPath by rememberSaveable { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    BackHandler(vm.mode != "tree" && !vm.busy) { vm.show("tree", "") }
    if (confirm.isNotBlank()) AlertDialog(onDismissRequest = { confirm = "" }, title = { Text(when (confirm) { "delete" -> "ファイルを削除しますか？"; "discard" -> "保存した作業を破棄しますか？"; else -> "新規ブランチへコミットしますか？" }) },
        text = { Text(if (confirm == "commit") "${vm.workspace?.branch}\n${vm.workspace?.changes?.size ?: 0}ファイル（編集中の内容も保存します）\n${vm.workspace?.message}" else "この操作を確認してください。") },
        confirmButton = { TextButton(onClick = { when (confirm) { "delete" -> vm.delete(); "discard" -> vm.discard(); else -> vm.commit() }; confirm = "" }) { Text("実行") } },
        dismissButton = { TextButton(onClick = { confirm = "" }) { Text("キャンセル") } })
    if (showNew) AlertDialog(onDismissRequest = { showNew = false }, title = { Text("新規ファイル") }, text = { OutlinedTextField(newPath, { newPath = it }, label = { Text("リポジトリからの相対パス") }) },
        confirmButton = { TextButton(onClick = { vm.newFile(newPath); showNew = false }, enabled = newPath.isNotBlank()) { Text("作成") } }, dismissButton = { TextButton(onClick = { showNew = false }) { Text("キャンセル") } })
    LazyColumn(Modifier.testTag("repository-list").fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Text("Repository", style = MaterialTheme.typography.headlineSmall)
            Text("${vm.ref} / ${vm.path}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.show("tree", "") }, enabled = !vm.busy) { Text("ファイル") }
                TextButton(onClick = { vm.show("branches", "") }, enabled = !vm.busy) { Text("Branch") }
                TextButton(onClick = { vm.show("tags", "") }, enabled = !vm.busy) { Text("Tag") }
                TextButton(onClick = { vm.show("history", "") }, enabled = !vm.busy) { Text("履歴") }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.offline) Text("保存済みの一覧を表示しています。編集には接続が必要です。")
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (vm.status.isNotBlank()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
        }
        when (vm.mode) {
            "file" -> {
                item {
                    Text(vm.source?.path.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(search, { search = it }, label = { Text("ファイル内検索") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (search.isNotEmpty()) Text("一致する行：" + vm.buffer.lines().mapIndexedNotNull { i, line -> (i + 1).takeIf { line.contains(search, true) } }.take(50).joinToString())
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(vm.buffer)) }) { Text("コピー") }
                        TextButton(onClick = { vm.show("history", vm.source?.path.orEmpty()) }, enabled = !vm.busy) { Text("履歴") }
                        TextButton(onClick = { vm.blame() }, enabled = !vm.busy && vm.source?.lastCommit?.isNotBlank() == true) { Text("Blame") }
                    }
                    Row {
                        FilterChip(selected = editing, onClick = { editing = !editing }, label = { Text(if (editing) "編集を終了" else "コードを編集") })
                        TextButton(onClick = { preview = !preview }) { Text("変更を確認") }
                    }
                    if (preview) {
                        Text("変更前", style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(vm.source?.content.orEmpty().take(12000), fontFamily = FontFamily.Monospace) }
                        Text("変更後", style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(vm.buffer.take(12000), fontFamily = FontFamily.Monospace) }
                        if (vm.buffer.length > 12000) Text("プレビューは先頭12,000文字です。全文はエディタで確認できます。")
                    }
                    if (editing) OutlinedTextField(vm.buffer, vm::edit, modifier = Modifier.testTag("repository-editor").fillMaxWidth().heightIn(min = 260.dp, max = 520.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), label = { Text("コード（UTF-8）") }, enabled = !vm.busy && vm.workspace?.attempted != true)
                    Row {
                        Button(onClick = vm::stage, enabled = !vm.busy && vm.workspace?.attempted != true) { Text("変更に追加") }
                        TextButton(onClick = { confirm = "delete" }, enabled = !vm.busy && vm.workspace?.attempted != true) { Text("削除") }
                    }
                }
                if (!editing) items(vm.buffer.lines().withIndex().toList()) { (index, line) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${index + 1}", Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        SelectionContainer { Text(highlight(line), fontFamily = FontFamily.Monospace) }
                    }
                }
            }
            "commit" -> item { SelectionContainer { Text(vm.detail?.let { "${it.text("title")}\n${it.text("id")}\n${it.text("author_name")}\n${it.text("created_at")}\n\n${it.text("message")}" }.orEmpty()) } }
            "blame" -> {
                items(vm.entries) { entry ->
                    val commit = entry.getJSONObject("commit")
                    Text("${commit.text("short_id")} · ${commit.text("author_name")}")
                    SelectionContainer { Text(entry.getJSONArray("lines").let { lines -> (0 until lines.length()).joinToString("\n") { lines.getString(it) } }, fontFamily = FontFamily.Monospace) }
                }
                item { TextButton(onClick = vm::returnToFile) { Text("ファイルに戻る") }; vm.next?.let { start -> TextButton(onClick = { vm.blame(start) }, enabled = !vm.busy) { Text("次の50行") } } }
            }
            else -> {
                item {
                    if (vm.mode in setOf("branches", "tags")) {
                        OutlinedTextField(vm.searching, vm::search, label = { Text("名前で検索") }); TextButton(onClick = vm::refresh, enabled = !vm.busy) { Text("検索") }
                    }
                    if (vm.mode == "tree") {
                        if (vm.path.isNotBlank()) TextButton(onClick = { vm.show("tree", vm.path.substringBeforeLast('/', "")) }, enabled = !vm.busy) { Text("上の階層") }
                        TextButton(onClick = { newPath = vm.path.takeIf { it.isNotBlank() }?.plus("/").orEmpty(); showNew = true }, enabled = !vm.busy && vm.workspace?.attempted != true) { Text("新規ファイル") }
                    }
                    if (!vm.busy && vm.entries.isEmpty()) Text("項目がありません")
                }
                items(vm.entries) { value ->
                    OutlinedButton(onClick = {
                        when (vm.mode) {
                            "branches", "tags" -> vm.chooseRef(value.getString("name"))
                            "history" -> vm.commitDetail(value.getString("id"))
                            else -> if (value.text("type") == "tree") vm.show("tree", value.getString("path")) else vm.openFile(value.getString("path"))
                        }
                    }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (vm.mode == "history") "${value.text("short_id")} ${value.text("title")}" else value.text("name"))
                    }
                }
                item { vm.next?.let { TextButton(onClick = vm::more, enabled = !vm.busy) { Text("さらに読み込む") } }; TextButton(onClick = vm::refresh, enabled = !vm.busy) { Text("再読み込み") } }
            }
        }
        vm.workspace?.let { workspace ->
            item {
                HorizontalDivider()
                Text("作業中の変更 (${workspace.changes.size})", style = MaterialTheme.typography.titleMedium)
                workspace.changes.forEach { Text("${when(it.action) { "create" -> "+"; "delete" -> "−"; else -> "編集" }} ${it.path}") }
                Text("保存先：${workspace.branch}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(workspace.message, vm::message, enabled = !vm.busy && !workspace.attempted, label = { Text("コミットメッセージ") }, modifier = Modifier.testTag("commit-message").fillMaxWidth())
                if (workspace.commit.isBlank()) Button(onClick = { confirm = "commit" }, enabled = !vm.busy && workspace.message.isNotBlank()) { Text(if (workspace.attempted) "送信結果を確認・再試行" else "コミットを確認") }
                else Button(onClick = { vm.createMr(openMr) }, enabled = !vm.busy) { Text("Merge Requestを作成") }
                TextButton(onClick = { confirm = "discard" }, enabled = !vm.busy) { Text("端末の作業を破棄") }
                Text("下書きはこのアカウント用に暗号化して端末に保存されます。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun highlight(line: String): AnnotatedString {
    val keyword = MaterialTheme.colorScheme.primary
    return remember(line, keyword) {
        buildAnnotatedString {
            val tokens = Regex("\\b(fun|val|var|class|data|if|else|return|import|package|def|const|let|function|public|private|true|false|null)\\b")
            var start = 0
            tokens.findAll(line.take(4096)).forEach { match ->
                append(line.substring(start, match.range.first))
                withStyle(SpanStyle(color = keyword)) { append(match.value) }
                start = match.range.last + 1
            }
            append(line.substring(start))
        }
    }
}
