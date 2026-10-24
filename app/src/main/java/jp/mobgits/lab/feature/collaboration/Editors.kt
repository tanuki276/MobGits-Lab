package jp.mobgits.lab.feature.collaboration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import jp.mobgits.lab.AppViewModel
import jp.mobgits.lab.core.network.*
import org.json.JSONObject

@Composable fun IssueFilters(vm: AppViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var labels by rememberSaveable { mutableStateOf("") }
    var assignee by rememberSaveable { mutableStateOf("") }
    var milestone by rememberSaveable { mutableStateOf("") }
    TextButton(onClick = { expanded = !expanded }) { Text("絞り込み（ラベル・担当者・マイルストーン）") }
    if (expanded) Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(labels, { labels = it }, label = { Text("ラベル（カンマ区切り）") })
        OutlinedTextField(assignee, { assignee = it }, label = { Text("担当者のユーザー名") })
        OutlinedTextField(milestone, { milestone = it }, label = { Text("マイルストーン名") })
        TextButton(onClick = { vm.filter(vm.state.filter.copy(labels = labels, assignee = assignee, milestone = milestone)) }) { Text("適用") }
    }
}

@Composable fun IssueEditor(vm: AppViewModel, create: Boolean = false) {
    val state = vm.state; val detail = if (create) null else state.detail
    var show by rememberSaveable(state.route.encode(), create) { mutableStateOf(false) }
    var title by rememberSaveable(state.route.encode(), create) { mutableStateOf(detail?.text("title").orEmpty()) }
    var description by rememberSaveable(state.route.encode(), create) { mutableStateOf(detail?.text("description").orEmpty()) }
    var labels by rememberSaveable(state.route.encode(), create) { mutableStateOf(detail?.optJSONArray("labels")?.let { a -> List(a.length()) { a.optString(it) }.joinToString(",") }.orEmpty()) }
    var due by rememberSaveable(state.route.encode(), create) { mutableStateOf(detail?.text("due_date").orEmpty()) }
    var assignee by rememberSaveable(state.route.encode(), create) { mutableStateOf(detail?.optJSONArray("assignees")?.optJSONObject(0)?.optLong("id")?.toString().orEmpty()) }
    var milestone by rememberSaveable(state.route.encode(), create) { mutableStateOf(detail?.optJSONObject("milestone")?.optLong("id")?.toString().orEmpty()) }
    var reviewer by rememberSaveable(state.route.encode(), create) { mutableStateOf(detail?.optJSONArray("reviewers")?.let { a -> List(a.length()) { a.getJSONObject(it).getLong("id") }.joinToString(",") }.orEmpty()) }
    val mr = state.route.kind == "mr"
    LaunchedEffect(state.notice) { if (state.notice == "保存しました") { show = false; if (create) { title = ""; description = "" } } }
    TextButton(onClick = { show = true }, enabled = !state.offline && !state.submitting && !state.loading) { Text(if (create) "＋ Issueを作成" else "編集") }
    if (show) AlertDialog(onDismissRequest = { if (!state.submitting) show = false }, title = { Text(if (create) "Issueを作成" else "編集") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(title, { title = it }, label = { Text("タイトル") }, modifier = Modifier.testTag("issue-title")) }
            item { OutlinedTextField(description, { description = it }, label = { Text("説明（Markdown）") }, minLines = 4, modifier = Modifier.testTag("issue-description")) }
            item { OutlinedTextField(labels, { labels = it }, label = { Text("ラベル（カンマ区切り）") }) }
            item { MetadataPicker(vm, "members", "担当者", assignee) { assignee = it } }
            item { MetadataPicker(vm, "milestones", "マイルストーン", milestone) { milestone = it } }
            if (mr) item { MetadataPicker(vm, "members", "レビュアー", reviewer) { reviewer = it } }
            else item { OutlinedTextField(due, { due = it }, label = { Text("期限（YYYY-MM-DD、空欄で解除）") }) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank() && !state.submitting, onClick = {
        val fields = mutableMapOf("title" to title, "description" to description, "labels" to labels,
            "assignee_ids[]" to assignee.ifBlank { "0" }, "milestone_id" to milestone.ifBlank { "0" })
        if (mr) fields["reviewer_ids[]"] = reviewer.ifBlank { "0" } else fields["due_date"] = due
        vm.save(fields, create)
    }) { Text(if (state.submitting) "保存中…" else "保存") } }, dismissButton = { TextButton(enabled = !state.submitting, onClick = { show = false }) { Text("閉じる（下書きを保持）") } })
}

@Composable private fun MetadataPicker(vm: AppViewModel, kind: String, label: String, selected: String, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var next by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    LaunchedEffect(open, page, applied) {
        if (open) {
            loading = true
            try { val result = vm.options(kind, page, applied); values = if (page == 1) result.entries else values + result.entries; next = result.next; error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    OutlinedButton(onClick = { open = true }) { Text("$label: ${if (selected.isBlank() || selected == "0") "なし" else "選択済み"}") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text("検索") })
            TextButton(onClick = { page = 1; applied = search }) { Text("検索する") }
            error?.let { Text(it) }
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(values) { value -> TextButton(onClick = { choose(value.getLong("id").toString()); open = false }) { Text(value.text("name").ifBlank { value.text("title") }.ifBlank { value.text("username") }) } }
                if (next != null) item { TextButton(enabled = !loading, onClick = { page = next!! }) { Text("さらに読み込む") } }
            }
        }
    }, confirmButton = { TextButton(onClick = { choose(""); open = false }) { Text("解除") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("戻る") } })
}

@Composable fun CommentEditor(vm: AppViewModel, thread: String? = null, position: jp.mobgits.lab.ReviewPosition? = null, finished: (() -> Unit)? = null) {
    val state = vm.state
    var body by rememberSaveable(state.route.encode(), thread, position?.toString()) { mutableStateOf("") }
    LaunchedEffect(state.notice) { if (state.notice == "コメントを投稿しました") { body = ""; finished?.invoke() } }
    Column(Modifier.padding(12.dp)) {
        OutlinedTextField(body, { body = it }, label = { Text(if (thread == null) "コメント（Markdown）" else "このスレッドへ返信") }, minLines = 3, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().testTag("comment-body"))
        Button(onClick = { vm.comment(body, thread, position) }, enabled = body.isNotBlank() && !state.submitting && !state.offline && !state.loading && !state.needsRefresh) { Text("投稿") }
    }
}
