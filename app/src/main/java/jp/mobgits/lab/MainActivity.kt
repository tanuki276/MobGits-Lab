package jp.mobgits.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MobGitsApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobGitsApp(vm: MobGitsViewModel = viewModel()) {
    val state = vm.state
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    when {
        state.auth == null -> LoginScreen(state.loading, snackbar, vm::login)
        state.selectedProject != null -> ProjectScreen(state, snackbar, vm::closeProject)
        else -> ProjectListScreen(state, snackbar, vm::logout, vm::openProject)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    loading: Boolean,
    snackbar: SnackbarHostState,
    onLogin: (String, String) -> Unit
) {
    var baseUrl by remember { mutableStateOf("https://gitlab.com") }
    var token by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MobGits Lab") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("GitLabに接続", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("日本語で使いやすい非公式GitLabクライアント")
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GitLab URL") },
                singleLine = true
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Personal Access Token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Text(
                "推奨スコープ: read_api。トークンはAndroid Keystoreを使って端末内に暗号化保存します。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { onLogin(baseUrl, token) },
                enabled = !loading && token.isNotBlank() && baseUrl.startsWith("https://"),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) CircularProgressIndicator() else Text("接続する")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectListScreen(
    state: AppState,
    snackbar: SnackbarHostState,
    onLogout: () -> Unit,
    onOpen: (GitLabProject) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("プロジェクト")
                        state.user?.let { Text("${it.name} (@${it.username})", style = MaterialTheme.typography.labelSmall) }
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, contentDescription = "ログアウト")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.projects.isEmpty() && !state.loading) {
                Text("参加中のプロジェクトがありません", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.projects, key = { it.id }) { project ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(project) }
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(project.name, fontWeight = FontWeight.Bold)
                                Text(project.pathWithNamespace, style = MaterialTheme.typography.bodySmall)
                                if (project.description.isNotBlank() && project.description != "null") {
                                    Text(project.description, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text("★ ${project.stars}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            if (state.loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreen(
    state: AppState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit
) {
    val project = state.selectedProject ?: return
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Issue", "MR", "Pipeline")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                project.pathWithNamespace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }

            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> IssueList(state.issues)
                    1 -> MergeRequestList(state.mergeRequests)
                    else -> PipelineList(state.pipelines)
                }
                if (state.loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun IssueList(issues: List<GitLabIssue>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(issues, key = { it.iid }) { issue ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("#${issue.iid} ${issue.title}", fontWeight = FontWeight.SemiBold)
                    Text("作成者: ${issue.author}", style = MaterialTheme.typography.bodySmall)
                    if (issue.labels.isNotEmpty()) Text(issue.labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MergeRequestList(items: List<GitLabMergeRequest>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.iid }) { mr ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("!${mr.iid} ${if (mr.draft) "[Draft] " else ""}${mr.title}", fontWeight = FontWeight.SemiBold)
                    Text("${mr.sourceBranch} → ${mr.targetBranch}", style = MaterialTheme.typography.bodySmall)
                    Text("作成者: ${mr.author}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PipelineList(items: List<GitLabPipeline>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { pipeline ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Pipeline #${pipeline.id}", fontWeight = FontWeight.SemiBold)
                        Text(pipeline.ref, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(pipeline.status.uppercase(), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
