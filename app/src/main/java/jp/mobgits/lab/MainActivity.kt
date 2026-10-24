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

    Scaffold(
        topBar = { TopAppBar(title = { Text(rootTitle(state.screen)) }) },
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
    if (projects.isEmpty()) return EmptyState("プロジェクトがありません", "参加中のGitLabプロジェクトがここに表示されます")
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
            Text("★ ${project.stars}  ·  ${project.defaultBranch}", style = MaterialTheme.typography.labelMedium)
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
                navigationIcon = { IconButton(onClick = vm::closeProject) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } }
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
