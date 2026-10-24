package jp.mobgits.lab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeReviewScreen(review: ReviewState, snackbar: SnackbarHostState, vm: MobGitsViewModel) {
    var tab by rememberSaveable(review.iid) { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<ReviewAction?>(null) }
    val snapshot = review.snapshot
    val detail = snapshot?.detail
    val busy = review.loading || review.submitting
    val ready = !busy && !review.stale && detail?.canSubmit == true
    BackHandler { if (!review.submitting) vm.closeReview() }
    // Confirmation always belongs to the displayed revision.
    LaunchedEffect(detail?.refs, review.stale) { confirm = null }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("!${review.iid} を${action.label}しますか？") },
            text = { Text("${detail?.source} → ${detail?.target}\n確認したコミット: ${detail?.refs?.head?.take(12)}\nGitLab上に反映されます。") },
            confirmButton = { TextButton(enabled = ready, onClick = { confirm = null; vm.reviewAction(action) }) { Text(action.label) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("戻る") } }
        )
    }
    review.commentPosition?.let { position ->
        AlertDialog(
            onDismissRequest = { vm.dismissReviewComment() },
            title = { Text("${position.newPath} · 旧${position.oldLine ?: "–"} / 新${position.newLine ?: "–"}") },
            text = {
                Column {
                    if (position.refs != detail?.refs) Text("この下書きは古い差分に紐づいています。行を選び直してください。")
                    OutlinedTextField(value = review.draft, onValueChange = vm::updateReviewDraft, enabled = !review.submitting,
                        label = { Text("インラインコメント") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp))
                    review.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = ready && review.draft.isNotBlank() && position.refs == detail?.refs,
                    onClick = vm::postReviewComment) { Text(if (review.submitting) "投稿中…" else "投稿") }
            },
            dismissButton = { TextButton(enabled = !review.submitting, onClick = vm::dismissReviewComment) { Text("閉じる（本文を保持）") } }
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("!${review.iid} レビュー") },
                navigationIcon = { IconButton(enabled = !review.submitting, onClick = vm::closeReview) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "MR一覧へ戻る") } },
                actions = { IconButton(enabled = !busy, onClick = vm::refreshReview) { Icon(Icons.Default.Refresh, "MRを再読み込み") } })
        }, snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            review.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            review.notice?.let { Text(it, Modifier.padding(12.dp)) }
            if (snapshot == null) {
                if (!busy) TextButton(onClick = vm::refreshReview) { Text("再試行") }
            } else {
                Text(detail!!.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                Text("${detail.source} → ${detail.target} · ${detail.state} · ${detail.mergeStatus}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = ready && snapshot.approval?.canApprove == true && snapshot.approval?.hasApproved != true,
                        onClick = { confirm = ReviewAction.APPROVE }) { Text(if (snapshot.approval?.hasApproved == true) "承認済み" else "Approve") }
                    Button(enabled = ready && detail.mergeable, onClick = { confirm = ReviewAction.MERGE }) { Text("Merge") }
                }
                TabRow(selectedTabIndex = tab) {
                    listOf("差分", "コメント", "概要").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                when (tab) {
                    0 -> ReviewDiffList(snapshot, ready) { diff, line -> vm.selectReviewLine(diff, line) }
                    1 -> ReviewComments(snapshot)
                    else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("確認中SHA: ${detail.refs.head}", fontFamily = FontFamily.Monospace) }
                        item { Text("承認者: ${snapshot.approval?.approvers?.joinToString()?.ifBlank { "なし" } ?: "取得できません"}") }
                        snapshot.approvalWarning?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                        if (detail.draft) item { Text("Draftのためマージできません") }
                        if (!detail.refs.complete) item { Text("差分準備中です。再読み込みしてください") }
                        item { SelectionContainer { Text(detail.description.ifBlank { "説明はありません" }) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewDiffList(snapshot: ReviewSnapshot, ready: Boolean, select: (ReviewDiff, DiffLine) -> Unit) {
    val inlineNotes = remember(snapshot.discussions) { snapshot.discussions.flatMap { it.notes }.filter { it.position != null }.groupBy { it.position } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("行をタップしてコメント · 旧 / 新 行番号\nGitLabの差分上限により一部が省略される場合があります。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        if (snapshot.diffs.isEmpty()) item { Text("表示できる差分はありません", Modifier.padding(16.dp)) }
        snapshot.diffs.forEachIndexed { fileIndex, diff ->
            item(key = "file-$fileIndex") {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
                    Text(if (diff.renamed) "${diff.oldPath} → ${diff.newPath}" else if (diff.deletedFile) diff.oldPath else diff.newPath, fontWeight = FontWeight.Bold)
                    Text(when { diff.newFile -> "追加ファイル"; diff.deletedFile -> "削除ファイル"; diff.renamed -> "名前変更"; else -> "変更ファイル" })
                    if (diff.unavailable) Text(when {
                        diff.tooLarge -> "サイズ上限のため差分を取得できません。GitLabで確認してください。"
                        diff.collapsed -> "この差分はGitLab側で省略されています。GitLabで確認してください。"
                        else -> "テキスト差分はありません（バイナリ・名前・権限のみの変更など）。"
                    })
                }
            }
            if (!diff.unavailable) items(diff.lines.size, key = { "line-$fileIndex-$it" }) { index ->
                val line = diff.lines[index]
                val background = when (line.kind) {
                    DiffKind.ADDED -> Color(0xFFDCFCE7)
                    DiffKind.REMOVED -> Color(0xFFFEE2E2)
                    DiffKind.HUNK -> Color(0xFFDBEAFE)
                    else -> MaterialTheme.colorScheme.surface
                }
                val foreground = if (line.kind in setOf(DiffKind.ADDED, DiffKind.REMOVED, DiffKind.HUNK)) Color(0xFF17202A) else MaterialTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth().background(background)
                    .clickable(enabled = ready && line.commentable, onClickLabel = "この行にコメント") { select(diff, line) }
                    .semantics { contentDescription = "旧行 ${line.oldLine ?: "なし"} 新行 ${line.newLine ?: "なし"} ${line.text}" }
                    .padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text("${line.oldLine ?: "–"} / ${line.newLine ?: "–"}", Modifier.width(88.dp), color = foreground, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(line.text, Modifier.weight(1f).horizontalScroll(rememberScrollState()), color = foreground, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                val position = if (line.commentable && snapshot.detail.refs.complete) ReviewPosition(
                    snapshot.detail.refs, diff.oldPath, diff.newPath, line.oldLine, line.newLine) else null
                val notes = inlineNotes[position].orEmpty()
                notes.forEach { note -> ReviewNoteCard(note) }
            }
        }
    }
}

@Composable
private fun ReviewComments(snapshot: ReviewSnapshot) {
    val notes = remember(snapshot.discussions) { snapshot.discussions.flatMap { it.notes } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (notes.isEmpty()) item { Text("コメントはありません") }
        items(notes, key = { it.id }) { note -> ReviewNoteCard(note) }
    }
}

@Composable
private fun ReviewNoteCard(note: ReviewNote) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("@${note.author}", fontWeight = FontWeight.SemiBold)
            note.position?.let { Text("${it.newPath} · 旧${it.oldLine ?: "–"} / 新${it.newLine ?: "–"} · ${it.refs.head.take(8)}", style = MaterialTheme.typography.labelSmall) }
            SelectionContainer { Text(note.body) }
        }
    }
}
