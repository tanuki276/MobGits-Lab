package jp.mobgits.lab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MobGitsViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOAuthIntent(intent)
        setContent {
            MaterialTheme {
                MobGitsApp(vm) { uri -> startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "mobgits" && data.host == "oauth") vm.handleOAuthCallback(data)
    }
}

@Composable
fun MobGitsApp(vm: MobGitsViewModel, openBrowser: (Uri) -> Unit) {
    val state = vm.state
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    when {
        state.auth == null -> LoginScreen(state.loading, snackbar, { vm.startOAuth()?.let(openBrowser) }, vm::loginPat)
        state.selectedProject != null -> ProjectScreen(state, snackbar, vm)
        else -> RootShell(state, snackbar, vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    loading: Boolean,
    snackbar: SnackbarHostState,
    onOAuth: () -> Unit,
    onPat: (String, String) -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("https://gitlab.com") }
    var token by remember { mutableStateOf("") }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Code, null, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(18.dp))
            Text("MobGits Lab", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("GitLabを、モバイルで本気で使う。", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(28.dp))

            Button(onClick = onOAuth, enabled = !loading && BuildConfig.GITLAB_CLIENT_ID.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Login, null)
                Spacer(Modifier.width(8.dp))
                Text("GitLab.comでログイン")
            }
            if (BuildConfig.GITLAB_CLIENT_ID.isBlank()) {
                Text("OAuth Client IDが未設定のため、下のPATログインを使用してください。", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "詳細ログインを閉じる" else "セルフホスト / PATでログイン")
            }

            if (showAdvanced) {
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("GitLab URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(token, { token = it }, label = { Text("Personal Access Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { onPat(baseUrl, token) }, enabled = token.isNotBlank() && baseUrl.startsWith("https://"), modifier = Modifier.fillMaxWidth()) {
                    Text("PATで接続")
                }
            }
            if (loading) {
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootShell(state: AppState, snackbar: SnackbarHostState, vm: MobGitsViewModel) {
    val tabs = listOf(
        RootScreen.HOME to (Icons.Default.Home to "ホーム"),
        RootScreen.INBOX to (Icons.Default.Notifications to "通知"),
        RootScreen.PROJECTS to (Icons.Default.Folder to "プロジェクト"),
        RootScreen.SEARCH to (Icons.Default.Search to "検索"),
        RootScreen.PROFILE to (Icons.Default.Person to "プロフィール")
    )
    var showCreateProject by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rootTitle(state.screen)) },
                actions = {
                    if (state.screen == RootScreen.PROJECTS) {
                        IconButton(onClick = { showCreateProject = true }) {
                            Icon(Icons.Default.Add, "プロジェクトを作成")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { (screen, pair) ->
                    NavigationBarItem(
                        selected = state.screen == screen,
                        onClick = { vm.selectRoot(screen) },
                        icon = { Icon(pair.first, pair.second) },
                        label = { Text(pair.second) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                RootScreen.HOME -> HomeScreen(state, vm::openProject)
                RootScreen.INBOX -> InboxScreen(state.todos, vm::markTodoDone)
                RootScreen.PROJECTS -> ProjectList(state.projects, vm::openProject)
                RootScreen.SEARCH -> SearchScreen(state, vm::search, vm::openProject)
                RootScreen.PROFILE -> ProfileScreen(state, vm::logout)
            }
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    if (showCreateProject) {
        ProjectEditorDialog(
            title = "新しいプロジェクト",
            initial = null,
            allowPath = true,
            onDismiss = { showCreateProject = false },
            onSave = {
                showCreateProject = false
                vm.createProject(it)
            }
        )
    }
}

@Composable
private fun HomeScreen(state: AppState, onOpen: (GitLabProject) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("こんにちは、${state.user?.name ?: ""}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("未処理の通知 ${state.todos.size}件")
        }
        item { Text("最近のプロジェクト", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(state.projects.take(8), key = { it.id }) { ProjectCard(it, onOpen) }
    }
}

@Composable
private fun InboxScreen(todos: List<GitLabTodo>, markDone: (Long) -> Unit) {
    if (todos.isEmpty()) return EmptyState("通知はありません", "GitLabのTo-Doがここに表示されます")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(todos, key = { it.id }) { todo ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(todo.title.ifBlank { todo.targetType }, fontWeight = FontWeight.SemiBold)
                    Text(todo.projectName, style = MaterialTheme.typography.bodySmall)
                    if (todo.body.isNotBlank()) Text(todo.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { markDone(todo.id) }) { Text("完了") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectList(projects: List<GitLabProject>, onOpen: (GitLabProject) -> Unit) {
    if (projects.isEmpty()) return EmptyState("プロジェクトがありません", "右上の＋から新しいプロジェクトを作成できます")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(projects, key = { it.id }) { ProjectCard(it, onOpen) }
    }
}

@Composable
private fun ProjectCard(project: GitLabProject, onOpen: (GitLabProject) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(project) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(project.name, fontWeight = FontWeight.Bold)
            Text(project.pathWithNamespace, style = MaterialTheme.typography.bodySmall)
            if (project.description.isNotBlank() && project.description != "null") Text(project.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${project.visibility.label}  ·  ★ ${project.stars}  ·  ${project.defaultBranch}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SearchScreen(state: AppState, onSearch: (String) -> Unit, onOpen: (GitLabProject) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            state.searchQuery,
            onSearch,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("プロジェクトを検索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.searchResults, key = { it.id }) { ProjectCard(it, onOpen) }
        }
    }
}

@Composable
private fun ProfileScreen(state: AppState, logout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
        Text(state.user?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("@${state.user?.username.orEmpty()}")
        Text(state.auth?.baseUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        AssistChip(onClick = {}, label = { Text(if (state.auth?.type == AuthType.OAUTH) "OAuth 2.0" else "PAT") })
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = logout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("ログアウト")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreen(state: AppState, snackbar: SnackbarHostState, vm: MobGitsViewModel) {
    val project = state.selectedProject ?: return
    var tab by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    val tabs = listOf("コード", "Issue", "MR", "CI/CD")

    if (state.openedFile != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.openedFile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = vm::closeFile) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val scroll = rememberScrollState()
                Text(
                    state.fileContent ?: "",
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxSize().horizontalScroll(scroll).padding(16.dp)
                )
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(project.name); Text(project.pathWithNamespace, style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = { IconButton(onClick = vm::closeProject) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                actions = {
                    IconButton(onClick = { showEditor = true }) {
                        Icon(Icons.Default.Edit, "プロジェクトを編集")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }) }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> RepositoryScreen(state, vm::openDirectory, vm::openFile)
                    1 -> IssueList(state.issues)
                    2 -> MergeRequestList(state.mergeRequests)
                    else -> PipelineList(state.pipelines)
                }
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showEditor) {
        ProjectEditorDialog(
            title = "プロジェクト設定",
            initial = project,
            allowPath = false,
            onDismiss = { showEditor = false },
            onSave = {
                showEditor = false
                vm.updateProject(it)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectEditorDialog(
    title: String,
    initial: GitLabProject?,
    allowPath: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProjectMutation) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var path by remember(initial?.id) { mutableStateOf("") }
    var description by remember(initial?.id) { mutableStateOf(initial?.description?.takeUnless { it == "null" }.orEmpty()) }
    var visibility by remember(initial?.id) { mutableStateOf(initial?.visibility ?: ProjectVisibility.PRIVATE) }
    var initializeReadme by remember(initial?.id) { mutableStateOf(true) }
    var visibilityMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("プロジェクト名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (allowPath) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("URLパス（任意）") },
                        supportingText = { Text("空欄ならGitLabが自動生成します") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("説明") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { visibilityMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Visibility, null)
                        Spacer(Modifier.width(8.dp))
                        Text("公開範囲: ${visibility.label}")
                    }
                    DropdownMenu(expanded = visibilityMenu, onDismissRequest = { visibilityMenu = false }) {
                        ProjectVisibility.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    visibility = option
                                    visibilityMenu = false
                                }
                            )
                        }
                    }
                }
                if (allowPath) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = initializeReadme, onCheckedChange = { initializeReadme = it })
                        Column {
                            Text("READMEで初期化")
                            Text("作成直後からmainブランチを使えます", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ProjectMutation(
                            name = name,
                            path = path,
                            description = description,
                            visibility = visibility,
                            initializeWithReadme = initializeReadme
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) { Text(if (initial == null) "作成" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun RepositoryScreen(state: AppState, openDirectory: (String) -> Unit, openFile: (GitLabTreeItem) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (state.repositoryPath.isNotBlank()) {
            TextButton(onClick = {
                val parent = state.repositoryPath.substringBeforeLast('/', "")
                openDirectory(parent)
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("上の階層") }
        }
        LazyColumn {
            items(state.repositoryTree, key = { it.path }) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    leadingContent = { Icon(if (item.type == "tree") Icons.Default.Folder else Icons.Default.Description, null) },
                    modifier = Modifier.clickable { if (item.type == "tree") openDirectory(item.path) else openFile(item) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun IssueList(issues: List<GitLabIssue>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(issues, key = { it.iid }) { issue ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Text("#${issue.iid} ${issue.title}", fontWeight = FontWeight.SemiBold)
                Text("@${issue.author}", style = MaterialTheme.typography.bodySmall)
                if (issue.labels.isNotEmpty()) Text(issue.labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            } }
        }
    }
}

@Composable
private fun MergeRequestList(items: List<GitLabMergeRequest>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.iid }) { mr ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Text("!${mr.iid} ${if (mr.draft) "[Draft] " else ""}${mr.title}", fontWeight = FontWeight.SemiBold)
                Text("${mr.sourceBranch} → ${mr.targetBranch}", style = MaterialTheme.typography.bodySmall)
                Text("@${mr.author}", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable
private fun PipelineList(items: List<GitLabPipeline>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { pipeline ->
            Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Pipeline #${pipeline.id}", fontWeight = FontWeight.SemiBold); Text(pipeline.ref) }
                Text(pipeline.status.uppercase(), fontWeight = FontWeight.Bold)
            } }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inbox, null, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun rootTitle(screen: RootScreen) = when (screen) {
    RootScreen.HOME -> "ホーム"
    RootScreen.INBOX -> "通知"
    RootScreen.PROJECTS -> "プロジェクト"
    RootScreen.SEARCH -> "検索"
    RootScreen.PROFILE -> "プロフィール"
}
