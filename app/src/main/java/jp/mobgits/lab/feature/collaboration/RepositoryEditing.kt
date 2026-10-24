package jp.mobgits.lab.feature.collaboration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.mobgits.lab.AppViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

@Composable
fun RepositoryEditing(vm: AppViewModel, createProject: Boolean = false) {
    val route = vm.state.route
    val account = vm.state.directory.activeAccount?.id ?: return
    var name by rememberSaveable(account, route.encode()) { mutableStateOf("") }
    var content by rememberSaveable(account, route.encode()) { mutableStateOf("") }
    var loaded by rememberSaveable(account, route.encode()) { mutableStateOf(false) }
    LaunchedEffect(vm.state.file) { if (!loaded && vm.state.file != null) { content = vm.state.file.orEmpty(); loaded = true } }
    var message by rememberSaveable(account, route.encode()) { mutableStateOf("") }
    var branch by rememberSaveable(account, route.encode()) { mutableStateOf("mobgits/edit-${UUID.randomUUID().toString().take(8)}") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var committed by rememberSaveable(account, route.encode()) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (createProject) "プロジェクト作成" else "ファイル編集", style = MaterialTheme.typography.headlineSmall)
        if (createProject) {
            OutlinedTextField(name, { name = it }, label = { Text("プロジェクト名") }, enabled = !busy)
            Text("自分の名前空間に非公開プロジェクトを作成します。")
        } else {
            Text(route.path)
            OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp), enabled = !busy && !committed, label = { Text("ファイル内容") })
            OutlinedTextField(branch, { branch = it }, label = { Text("新しいブランチ") }, enabled = !busy && !committed)
            OutlinedTextField(message, { message = it }, label = { Text("コミットメッセージ / MRタイトル") }, enabled = !busy && !committed)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled = !busy && !vm.state.loading && !vm.state.offline && if (createProject) name.isNotBlank() else message.isNotBlank() && branch.isNotBlank() && branch != route.ref,
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val data = vm.graph.resources
                        if (createProject) {
                            val result = JSONObject(data.write(account, "/api/v4/projects", "POST", mapOf("name" to name, "visibility" to "private", "initialize_with_readme" to "true")))
                            vm.navigate(Destination("project", result.getLong("id")), true)
                        } else {
                            if (!committed) {
                                val path = "/api/v4/projects/${route.project}/repository/files/${query(route.path)}"
                                val latest = data.read(account, "$path?ref=${query(route.ref)}", cached = false)
                                val file = JSONObject(latest.json)
                                val original = String(android.util.Base64.decode(file.getString("content"), android.util.Base64.DEFAULT), Charsets.UTF_8)
                                check(original == vm.state.file) { "元ファイルが変更されています。最新の内容を確認してください。" }
                                data.write(account, path, "PUT", mapOf("branch" to branch, "start_branch" to route.ref, "content" to content, "commit_message" to message, "last_commit_id" to file.getString("last_commit_id")))
                                committed = true
                            }
                            val mr = JSONObject(data.write(account, "/api/v4/projects/${route.project}/merge_requests", "POST", mapOf("source_branch" to branch, "target_branch" to route.ref, "title" to message)))
                            vm.navigate(Destination("mr", route.project, mr.getLong("iid")), true)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        error = (if (committed) "コミット済み。MR作成を再試行できます。\n" else "") + e.message
                    } finally { busy = false }
                }
            }) { Text(if (createProject) "非公開プロジェクトを作成" else if (committed) "MR作成を再試行" else "新規ブランチへコミットしてMR作成") }
    }
}
