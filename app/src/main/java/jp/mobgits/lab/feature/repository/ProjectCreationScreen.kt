package jp.mobgits.lab.feature.repository

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.mobgits.lab.AppViewModel
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.Destination
import kotlinx.coroutines.launch
import org.json.*

@Composable
fun ProjectCreationScreen(vm: AppViewModel) {
    val account = vm.state.directory.activeAccount?.id ?: return
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(account) { mutableStateOf("") }
    var path by rememberSaveable(account) { mutableStateOf("") }
    var description by rememberSaveable(account) { mutableStateOf("") }
    var visibility by rememberSaveable(account) { mutableStateOf("private") }
    var namespace by rememberSaveable(account) { mutableStateOf(0L) }
    var namespacePath by rememberSaveable(account) { mutableStateOf("") }
    var attempted by rememberSaveable(account) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var namespaces by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var next by remember { mutableStateOf<Int?>(1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun loadNamespaces() {
        busy = true; error = null
        scope.launch {
            try {
                val result = vm.graph.resources.read(account, "/api/v4/namespaces?per_page=20&page=${next ?: 1}", false)
                namespaces = namespaces + JSONArray(result.json).objects(); next = result.nextPage
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(account) { loadNamespaces() }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("プロジェクトを作成しますか？") },
        text = { Text("$namespacePath/$path\n公開範囲：$visibility\nREADMEで初期化します。") },
        confirmButton = { TextButton(onClick = {
            confirm = false; busy = true; error = null
            scope.launch {
                try {
                    val existing = try { JSONObject(vm.graph.resources.read(account, "/api/v4/projects/${query("$namespacePath/$path")}", false).json) }
                    catch (e: ApiFailure) { if (e.status == 404) null else throw e }
                    if (existing != null) {
                        check(attempted) { "同じパスのプロジェクトが存在します。名前を変更してください。" }
                        vm.navigate(Destination("project", existing.getLong("id")), true)
                    } else {
                        attempted = true
                        val id = RepositoryUseCase(vm.graph.resources).createProject(account, name, path, namespace, description, visibility)
                        vm.navigate(Destination("project", id), true)
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message }
                finally { busy = false }
            }
        }) { Text("実行") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("プロジェクト作成", style = MaterialTheme.typography.headlineSmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(name, { name = it }, label = { Text("プロジェクト名") }, enabled = !busy && !attempted, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(path, { path = it }, label = { Text("URLパス（英数字・ハイフン）") }, enabled = !busy && !attempted, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("説明") }, enabled = !busy && !attempted, modifier = Modifier.fillMaxWidth())
        Text("作成先：${namespacePath.ifBlank { "選択してください" }}")
        namespaces.forEach { value ->
            OutlinedButton(onClick = { namespace = value.getLong("id"); namespacePath = value.getString("full_path") }, enabled = !busy && !attempted) { Text(value.getString("full_path")) }
        }
        if (next != null) TextButton(onClick = ::loadNamespaces, enabled = !busy) { Text("作成先を読み込む") }
        Row { listOf("private" to "非公開", "internal" to "内部", "public" to "公開").forEach { (value, label) ->
            FilterChip(selected = visibility == value, onClick = { visibility = value }, enabled = !busy && !attempted, label = { Text(label) })
        } }
        Text("権限とサーバー設定によって利用できる作成先・公開範囲が異なります。")
        Button(onClick = { confirm = true }, enabled = !busy && namespace > 0 && name.isNotBlank() && path.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { Text(if (attempted) "作成結果を確認・再試行" else "作成内容を確認") }
        if (attempted) Text("通信が途切れた場合は同じパスのプロジェクトを確認します。確認できたプロジェクトを開きます。")
    }
}
