package jp.mobgits.lab.feature.collaboration

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import jp.mobgits.lab.*
import jp.mobgits.lab.core.auth.SessionStatus
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.auth.AuthScreen
import jp.mobgits.lab.feature.inbox.Notifications
import jp.mobgits.lab.feature.settings.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobGitsShell(vm: AppViewModel, open: (String) -> Unit, biometric: (Boolean) -> Unit) {
    val state = vm.state
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.flag("enabled", granted); Notifications.schedule(context, granted)
    }
    val notifications: (Boolean) -> Unit = { value ->
        if (value && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else { vm.flag("enabled", value); Notifications.schedule(context, value) }
    }
    if (state.directory.activeAccount == null) { AuthScreen(vm, open); return }
    val root = state.route.kind in setOf("home", "projects", "inbox", "search", "settings")
    BackHandler(enabled = !root) { vm.back() }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(when (state.route.kind) {
            "home" -> "ホーム"; "projects" -> "プロジェクト"; "inbox" -> "Inbox"; "search" -> "検索"; "settings" -> "設定"
            "issues" -> "Issues"; "mrs" -> "Merge Requests"; "pipelines" -> "Pipelines"; "file" -> state.route.path.substringAfterLast('/')
            else -> state.detail?.text("title")?.ifBlank { state.detail?.text("name").orEmpty() }?.ifBlank { state.route.kind } ?: state.route.kind
        }) }, navigationIcon = { if (!root) IconButton(onClick = vm::back, enabled = !state.submitting) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
            actions = { IconButton(onClick = { vm.reload() }, enabled = !state.loading && !state.submitting) { Icon(Icons.Default.Refresh, "再読み込み") } })
    }, bottomBar = { if (root) NavigationBar {
        listOf("home" to Icons.Default.Home, "projects" to Icons.Default.Folder, "inbox" to Icons.Default.Notifications,
            "search" to Icons.Default.Search, "settings" to Icons.Default.Settings).forEach { (kind, icon) ->
            NavigationBarItem(state.route.kind == kind, { vm.navigate(Destination(kind), true) }, icon = { Icon(icon, kind) }, label = { Text(when(kind) { "home" -> "ホーム"; "projects" -> "Projects"; "inbox" -> "Inbox"; "search" -> "検索"; else -> "設定" }) })
        }
    } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading || state.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.offline) Text("オフライン：保存した内容を表示しています。書き込みはできません。", Modifier.padding(8.dp))
            if (state.session is SessionStatus.ReauthenticationRequired) {
                TextButton(onClick = { vm.navigate(Destination("settings"), true) }) { Text(if ((state.session as? SessionStatus.ReauthenticationRequired)?.oauthConfigurationChanged == true) "OAuth設定が更新されました。設定からもう一度ログインしてください" else "セッションが切れました。設定から再認証してください") }
            }
            state.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, Modifier.padding(8.dp)) }
            if (state.route.kind == "projects") TextButton(onClick = { vm.navigate(Destination("create-project")) }) { Text("プロジェクトを作成") }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val contentModifier = if (maxWidth > 840.dp) Modifier.widthIn(max = 1000.dp).fillMaxHeight() else Modifier.fillMaxSize()
                Box(contentModifier.align(Alignment.TopCenter)) {
                    when (state.route.kind) {
                        "settings" -> SettingsScreen(vm, open, biometric, notifications)
                        "project" -> ProjectOverview(vm)
                        "issue", "mr", "pipeline", "job" -> EntityDetailScreen(vm)
                        "file" -> RepositoryEditing(vm)
                        "create-project" -> RepositoryEditing(vm, createProject = true)
                        else -> EntityListScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable private fun ProjectOverview(vm: AppViewModel) {
    val value = vm.state.detail
    val d = vm.state.route
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Markdown(value?.text("description").orEmpty()) }
        item { listOf("issues" to "Issues", "mrs" to "Merge Requests", "pipelines" to "CI/CD", "tree" to "Repository").forEach { (kind, label) ->
            OutlinedButton(onClick = { vm.navigate(Destination(kind, d.project, ref = value?.text("default_branch")?.ifBlank { "main" } ?: "main")) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } }
    }
}

@Composable fun EntityListScreen(vm: AppViewModel) {
    val state = vm.state; val d = state.route
    var search by rememberSaveable(d.kind) { mutableStateOf("") }
    Column {
        if (d.kind in setOf("search", "issues", "mrs")) {
            OutlinedTextField(search, { search = it }, label = { Text("検索") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            TextButton(onClick = { vm.filter(state.filter.copy(search = search)) }) { Text("検索する") }
        }
        if (d.kind in setOf("issues", "mrs")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("opened" to "Opened", "closed" to "Closed", "all" to "すべて").forEach { (value, label) ->
                    FilterChip(state.filter.state == value, { vm.filter(state.filter.copy(state = value)) }, label = { Text(label) })
                }
            }
            Row {
                TextButton(onClick = { vm.filter(state.filter.copy(scope = "assigned_to_me")) }) { Text("担当") }
                TextButton(onClick = { vm.filter(state.filter.copy(scope = "created_by_me")) }) { Text("作成者が自分") }
                TextButton(onClick = { vm.filter(ListFilter()) }) { Text("解除") }
            }
            if (d.kind == "issues") {
                IssueFilters(vm)
                IssueEditor(vm, create = true)
            }
        }
        if (d.kind == "tree" && d.path.isNotBlank()) TextButton(onClick = { vm.navigate(d.copy(path = d.path.substringBeforeLast('/', ""))) }) { Text("上の階層") }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.entries.isEmpty() && !state.loading) item { EmptyMessage() }
            itemsIndexed(state.entries, key = { index, value -> "${value.optLong("id")}-${value.text("path")}-${index}" }) { _, value ->
                if (d.kind == "inbox") InboxCard(value, vm)
                else Card(onClick = {
                    val next = when(d.kind) {
                        "home", "projects", "search" -> Destination("project", value.getLong("id"))
                        "issues" -> Destination("issue", d.project, value.getLong("iid"))
                        "mrs" -> Destination("mr", d.project, value.getLong("iid"))
                        "pipelines" -> Destination("pipeline", d.project, value.getLong("id"))
                        "tree" -> d.copy(kind = if (value.text("type") == "tree") "tree" else "file", path = value.text("path"))
                        else -> d
                    }; vm.navigate(next)
                }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text(value.text("title").ifBlank { value.text("name") }.ifBlank { "#${value.optLong("id")}" }) },
                        supportingContent = { Text(value.text("path_with_namespace").ifBlank { value.text("state") }.ifBlank { "${value.text("ref")} ${value.text("status")}" }) },
                        leadingContent = { value.text("avatar_url").takeIf { it.startsWith("https://") }?.let { coil.compose.AsyncImage(it, null, Modifier.size(40.dp)) } })
                }
            }
            if (state.next != null) item { OutlinedButton(onClick = vm::more, enabled = !state.loading) { Text("さらに読み込む") } }
        }
    }
}
