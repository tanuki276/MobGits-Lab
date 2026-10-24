package jp.mobgits.lab.feature.collaboration

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.mobgits.lab.AppViewModel
import jp.mobgits.lab.core.network.text
import org.json.JSONObject

fun todoDestination(todo: JSONObject): Destination? {
    val project = todo.optJSONObject("project")?.optLong("id") ?: return null
    val target = todo.optJSONObject("target") ?: return null
    val kind = when(todo.text("target_type")) { "Issue" -> "issue"; "MergeRequest" -> "mr"; "Pipeline" -> "pipeline"; else -> return null }
    val id = if (kind == "pipeline") target.optLong("id") else target.optLong("iid")
    return Destination(kind, project, id).takeIf { project > 0 && id > 0 }
}
fun todoCategory(todo: JSONObject): String = when(todo.text("action_name")) {
    "review_requested", "approval_required" -> "レビュー依頼"
    "assigned" -> "担当に指定"
    "mentioned", "directly_addressed" -> "メンション"
    else -> when(todo.text("target_type")) { "Issue" -> "Issue"; "MergeRequest" -> "Merge Request"; "Pipeline" -> "Pipeline"; else -> "その他" }
}
@Composable fun InboxCard(todo: JSONObject, vm: AppViewModel) {
    val target = todoDestination(todo)
    Card(onClick = { target?.let { vm.navigate(it) } }, enabled = target != null, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(todoCategory(todo), style = MaterialTheme.typography.labelLarge)
            Text(todo.optJSONObject("target")?.text("title").orEmpty().ifBlank { todo.text("body") })
            Text(todo.optJSONObject("project")?.text("path_with_namespace").orEmpty())
            TextButton(onClick = { vm.done(todo.getLong("id")) }, enabled = !vm.state.offline && !vm.state.submitting) { Text("完了") }
        }
    }
}
