package jp.mobgits.lab.feature.collaboration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import jp.mobgits.lab.*
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.diff.DiffScreen
import org.json.JSONObject

@Composable fun EntityDetailScreen(vm: AppViewModel) {
    val state = vm.state; val d = state.route; val value = state.detail ?: return
    val ready = !state.loading && !state.submitting && !state.offline && !state.needsRefresh
    val panels = when(d.kind) {
        "mr" -> listOf("overview" to "概要", "diffs" to "差分", "commits" to "Commits", "discussions" to "会話", "pipelines" to "CI")
        "issue" -> listOf("overview" to "概要", "discussions" to "会話", "activity" to "履歴")
        "pipeline" -> listOf("overview" to "概要", "jobs" to "Jobs")
        else -> listOf("overview" to "概要・ログ")
    }
    Column {
        ScrollableTabRow(selectedTabIndex = panels.indexOfFirst { it.first == state.panel }.coerceAtLeast(0)) {
            panels.forEach { (key, label) -> Tab(state.panel == key, { if (key == "logs") vm.panel("overview") else vm.panel(key); if (key == "logs") vm.loadLog() }, text = { Text(label) }) }
        }
        if (state.panel == "diffs") { DiffScreen(vm); return@Column }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.panel == "overview") {
                item { Text(value.text("title").ifBlank { value.text("name") }.ifBlank { "${d.kind} #${d.id}" }, style = MaterialTheme.typography.headlineSmall) }
                item {
                    listOf("state", "status", "ref", "sha", "detailed_merge_status", "duration", "created_at", "finished_at", "due_date").forEach { key ->
                        value.text(key).takeIf { it.isNotBlank() }?.let { Text("${fieldLabel(key)}: $it") }
                    }
                    listOf("author", "user", "assignee", "milestone", "runner").forEach { key -> value.optJSONObject(key)?.let { Text("${fieldLabel(key)}: ${it.text("name").ifBlank { it.text("title") }.ifBlank { it.text("description") }}") } }
                    listOf("assignees", "reviewers").forEach { key -> value.optJSONArray(key)?.let { a -> Text("${fieldLabel(key)}: ${a.objects().joinToString { it.text("name") }}") } }
                    value.optJSONArray("labels")?.let { Text("ラベル: ${List(it.length()) { i -> it.optString(i) }.joinToString()}") }
                    if (d.kind == "mr") {
                        Text("${value.text("source_branch")} → ${value.text("target_branch")}")
                        Text(if (value.optBoolean("has_conflicts")) "競合があります" else "競合なし")
                        value.optJSONObject("head_pipeline")?.let { Text("Pipeline: ${it.text("status")}") }
                        Text("承認者: ${state.approval?.optJSONArray("approved_by")?.objects()?.joinToString { it.optJSONObject("user")?.text("username").orEmpty() }.orEmpty()}")
                    }
                }
                item { Markdown(value.text("description")) }
                if (d.kind in setOf("issue", "mr")) {
                    item { IssueEditor(vm) }
                    item { ConfirmAction(if (value.text("state") == "opened") "Close" else "Reopen", ready && value.text("state") != "merged") {
                        vm.save(mapOf("state_event" to if (value.text("state") == "opened") "close" else "reopen"))
                    } }
                    if (d.kind == "mr") {
                        item { ConfirmAction(if (value.optBoolean("draft")) "Draftを解除" else "Draftにする", ready && value.text("state") == "opened") {
                            vm.save(mapOf("title" to if (value.optBoolean("draft")) value.text("title").replace(Regex("^(Draft:|WIP:)\\s*", RegexOption.IGNORE_CASE), "") else "Draft: ${value.text("title")}"))
                        } }
                        item { ConfirmAction(if (state.approval?.optBoolean("user_has_approved") == true) "Unapprove" else "Approve", ready && state.approval != null && (state.approval?.optBoolean("user_can_approve") == true || state.approval?.optBoolean("user_has_approved") == true)) {
                            vm.review(if (state.approval?.optBoolean("user_has_approved") == true) "unapprove" else "approve", vm.refs())
                        } }
                        item { val refs = vm.refs(); ConfirmAction("Merge", ready && refs.complete && value.optJSONObject("user")?.optBoolean("can_merge") == true && !value.optBoolean("draft") && value.text("detailed_merge_status") == "mergeable", "${value.text("source_branch")} → ${value.text("target_branch")}\n確認したSHA: ${refs.head.take(12)}") { vm.review("merge", refs) } }
                    }
                    item { CommentEditor(vm) }
                }
                if (d.kind == "pipeline") item {
                    ConfirmAction("Pipeline Retry", ready) { vm.jobAction("retry") }
                    ConfirmAction("Pipeline Cancel", ready && value.text("status") in setOf("running", "pending", "created", "waiting_for_resource")) { vm.jobAction("cancel") }
                }
                if (d.kind == "job") {
                    item {
                        val status = value.text("status")
                        if (GitLabJob(d.id, value.text("name"), value.text("stage"), status).allows(JobAction.RETRY)) ConfirmAction("Retry", ready) { vm.jobAction("retry") }
                        if (GitLabJob(d.id, value.text("name"), value.text("stage"), status).allows(JobAction.CANCEL)) ConfirmAction("Cancel", ready) { vm.jobAction("cancel") }
                        Text("Stage: ${value.text("stage")}")
                        value.optJSONArray("artifacts")?.objects()?.forEach { Text("Artifact: ${it.text("filename")} (${it.optLong("size")} bytes)") }
                        OutlinedButton(onClick = vm::loadLog, enabled = ready) { Text(if (state.log.isEmpty()) "ログを読み込む" else "次のログ／更新") }
                        Text("読み込み済み: ${state.logOffset} bytes（直近1MiBを表示）")
                    }
                    itemsIndexed(state.log) { _, chunk -> SelectionContainer { Text(chunk, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                }
            } else {
                if (state.entries.isEmpty() && !state.loading) item { EmptyMessage() }
                itemsIndexed(state.entries, key = { index, entry -> "${entry.text("id")}-${index}" }) { _, entry ->
                    when(state.panel) {
                        "discussions" -> DiscussionCard(entry, vm)
                        "activity" -> Column { Text("@${entry.optJSONObject("author")?.text("username").orEmpty()} · ${entry.text("created_at")}"); Markdown(entry.text("body")) }
                        "jobs", "pipelines" -> Card(onClick = { vm.navigate(Destination(if (state.panel == "jobs") "job" else "pipeline", d.project, entry.getLong("id"))) }) {
                            ListItem(headlineContent = { Text(entry.text("name").ifBlank { "Pipeline #${entry.optLong("id")}" }) }, supportingContent = { Text("${entry.text("stage")} ${entry.text("status")}") })
                        }
                        "commits" -> Column { Text(entry.text("title")); Text("${entry.text("short_id")} · ${entry.text("author_name")}"); Markdown(entry.text("message")) }
                    }
                }
                if (state.next != null) item { OutlinedButton(onClick = vm::more, enabled = !state.loading) { Text("さらに読み込む") } }
            }
        }
    }
}
@Composable private fun DiscussionCard(entry: JSONObject, vm: AppViewModel) {
    val notes = entry.optJSONArray("notes")?.objects().orEmpty()
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        notes.forEach { note ->
            Text("@${note.optJSONObject("author")?.text("username").orEmpty()} · ${note.text("created_at")}")
            note.optJSONObject("position")?.let { Text("${it.text("new_path")} · 旧${it.text("old_line")} / 新${it.text("new_line")}") }
            Markdown(note.text("body"))
        }
        if (vm.state.route.kind == "mr" && notes.any { it.optBoolean("resolvable") }) {
            val resolved = notes.filter { it.optBoolean("resolvable") }.all { it.optBoolean("resolved") }
            ConfirmAction(if (resolved) "未解決に戻す" else "解決済みにする", !vm.state.offline && !vm.state.loading && !vm.state.submitting) { vm.resolve(entry.getString("id"), !resolved) }
        }
        CommentEditor(vm, entry.getString("id"))
    } }
}
private fun fieldLabel(key: String) = when(key) { "state" -> "状態"; "status" -> "実行状態"; "duration" -> "所要時間（秒）"; "created_at" -> "作成日時"; "finished_at" -> "終了日時"; "due_date" -> "期限"; "author" -> "作成者"; "assignee", "assignees" -> "担当者"; "reviewers" -> "レビュアー"; "milestone" -> "マイルストーン"; "runner" -> "Runner"; "detailed_merge_status" -> "マージ状態"; else -> key }
