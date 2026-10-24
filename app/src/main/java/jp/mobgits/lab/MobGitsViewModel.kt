package jp.mobgits.lab

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class MobGitsViewModel(application: Application) : AndroidViewModel(application) {
    private val api = GitLabApi()
    private val tokenStore = TokenStore(application)
    private val oauth = OAuthManager()
    private var pendingOAuth: OAuthManager.PendingAuth? = null

    var state by mutableStateOf(AppState())
        private set

    init {
        tokenStore.load()?.let { restoreSession(it) }
    }

    fun startOAuth(baseUrl: String = "https://gitlab.com"): Uri? {
        return runCatching {
            val (uri, pending) = oauth.createAuthorizationUri(
                baseUrl = baseUrl,
                clientId = BuildConfig.GITLAB_CLIENT_ID,
                redirectUri = BuildConfig.OAUTH_REDIRECT_URI
            )
            pendingOAuth = pending
            state = state.copy(error = null)
            uri
        }.onFailure { state = state.copy(error = it.message ?: "OAuthを開始できません") }.getOrNull()
    }

    fun handleOAuthCallback(uri: Uri) {
        val pending = pendingOAuth ?: return
        val returnedState = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        if (error != null) {
            state = state.copy(error = error)
            pendingOAuth = null
            return
        }
        if (returnedState != pending.state || code.isNullOrBlank()) {
            state = state.copy(error = "OAuth応答を検証できませんでした")
            pendingOAuth = null
            return
        }

        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                oauth.exchangeCode(pending, BuildConfig.GITLAB_CLIENT_ID, BuildConfig.OAUTH_REDIRECT_URI, code)
            }.onSuccess { tokens ->
                tokenStore.saveOAuth(pending.baseUrl, tokens)
                pendingOAuth = null
                loadSession(tokenStore.load()!!)
            }.onFailure {
                pendingOAuth = null
                state = state.copy(loading = false, error = it.message ?: "OAuth認証に失敗しました")
            }
        }
    }

    fun loginPat(baseUrl: String, token: String) {
        val auth = StoredAuth(baseUrl.trim().trimEnd('/'), token, null, null, AuthType.PAT)
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                api.currentUser(auth)
                tokenStore.savePat(auth.baseUrl, auth.accessToken)
                auth
            }.onSuccess(::loadSession)
                .onFailure { state = state.copy(loading = false, error = it.message ?: "接続に失敗しました") }
        }
    }

    private fun restoreSession(auth: StoredAuth) {
        state = state.copy(loading = true)
        viewModelScope.launch {
            runCatching { freshAuth(auth) }
                .onSuccess(::loadSession)
                .onFailure {
                    tokenStore.clear()
                    state = AppState(error = "セッションの有効期限が切れました。もう一度ログインしてください")
                }
        }
    }

    private fun loadSession(auth: StoredAuth) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            runCatching {
                val fresh = freshAuth(auth)
                coroutineScope {
                    val user = async { api.currentUser(fresh) }
                    val projects = async { api.projects(fresh) }
                    val todos = async { api.todos(fresh) }
                    SessionData(fresh, user.await(), projects.await(), todos.await())
                }
            }.onSuccess { data ->
                state = state.copy(
                    auth = data.auth,
                    user = data.user,
                    projects = data.projects,
                    todos = data.todos,
                    loading = false,
                    screen = RootScreen.HOME
                )
            }.onFailure { state = state.copy(loading = false, error = it.message ?: "読み込みに失敗しました") }
        }
    }

    private suspend fun freshAuth(auth: StoredAuth): StoredAuth {
        if (!auth.needsRefresh()) return auth
        val refresh = auth.refreshToken ?: throw IllegalStateException("再認証が必要です")
        val tokens = oauth.refresh(auth.baseUrl, BuildConfig.GITLAB_CLIENT_ID, refresh)
        tokenStore.saveOAuth(auth.baseUrl, tokens)
        return tokenStore.load() ?: throw IllegalStateException("認証情報を保存できませんでした")
    }

    fun selectRoot(screen: RootScreen) {
        state = state.copy(screen = screen, selectedProject = null, openedFile = null, fileContent = null)
        if (screen == RootScreen.INBOX) refreshTodos()
    }

    fun refreshTodos() {
        val auth = state.auth ?: return
        viewModelScope.launch {
            runCatching { api.todos(freshAuth(auth)) }
                .onSuccess { state = state.copy(todos = it) }
                .onFailure { state = state.copy(error = it.message) }
        }
    }

    fun markTodoDone(id: Long) {
        val auth = state.auth ?: return
        viewModelScope.launch {
            runCatching { api.markTodoDone(freshAuth(auth), id) }
                .onSuccess { state = state.copy(todos = state.todos.filterNot { it.id == id }) }
                .onFailure { state = state.copy(error = it.message) }
        }
    }

    fun search(query: String) {
        state = state.copy(searchQuery = query)
        val auth = state.auth ?: return
        if (query.isBlank()) {
            state = state.copy(searchResults = emptyList())
            return
        }
        viewModelScope.launch {
            runCatching { api.searchProjects(freshAuth(auth), query) }
                .onSuccess { state = state.copy(searchResults = it) }
                .onFailure { state = state.copy(error = it.message) }
        }
    }

    fun openProject(project: GitLabProject) {
        val auth = state.auth ?: return
        state = state.copy(selectedProject = project, loading = true, repositoryPath = "", openedFile = null, fileContent = null)
        viewModelScope.launch {
            runCatching {
                val fresh = freshAuth(auth)
                coroutineScope {
                    val issues = async { api.issues(fresh, project.id) }
                    val mrs = async { api.mergeRequests(fresh, project.id) }
                    val pipelines = async { api.pipelines(fresh, project.id) }
                    val tree = async { api.repositoryTree(fresh, project.id) }
                    ProjectData(issues.await(), mrs.await(), pipelines.await(), tree.await())
                }
            }.onSuccess {
                state = state.copy(
                    loading = false,
                    issues = it.issues,
                    mergeRequests = it.mergeRequests,
                    pipelines = it.pipelines,
                    repositoryTree = it.tree
                )
            }.onFailure { state = state.copy(loading = false, error = it.message ?: "プロジェクトを開けませんでした") }
        }
    }

    fun openDirectory(path: String) {
        val auth = state.auth ?: return
        val project = state.selectedProject ?: return
        state = state.copy(loading = true, repositoryPath = path, openedFile = null, fileContent = null)
        viewModelScope.launch {
            runCatching { api.repositoryTree(freshAuth(auth), project.id, path, project.defaultBranch) }
                .onSuccess { state = state.copy(loading = false, repositoryTree = it) }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    fun openFile(item: GitLabTreeItem) {
        val auth = state.auth ?: return
        val project = state.selectedProject ?: return
        state = state.copy(loading = true, openedFile = item, fileContent = null)
        viewModelScope.launch {
            runCatching { api.rawFile(freshAuth(auth), project.id, item.path, project.defaultBranch) }
                .onSuccess { state = state.copy(loading = false, fileContent = it) }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    private var ciRequest = 0

    fun openPipeline(pipeline: GitLabPipeline) {
        val auth = state.auth ?: return
        val project = state.selectedProject ?: return
        val request = ++ciRequest
        state = state.copy(selectedPipeline = pipeline, selectedJob = null, jobs = emptyList(), jobLog = null, ciLoading = true)
        viewModelScope.launch {
            runCatching { api.pipelineJobs(freshAuth(auth), project.id, pipeline.id) }
                .onSuccess { if (request == ciRequest) state = state.copy(jobs = it, ciLoading = false) }
                .onFailure { if (request == ciRequest) state = state.copy(ciLoading = false, error = it.message) }
        }
    }

    fun openJob(job: GitLabJob) {
        val auth = state.auth ?: return
        val project = state.selectedProject ?: return
        val request = ++ciRequest
        state = state.copy(selectedJob = job, jobLog = null, ciLoading = true)
        viewModelScope.launch {
            runCatching { api.jobTrace(freshAuth(auth), project.id, job.id) }
                .onSuccess { if (request == ciRequest) state = state.copy(jobLog = it, ciLoading = false) }
                .onFailure { if (request == ciRequest) state = state.copy(ciLoading = false, error = it.message) }
        }
    }

    fun runJobAction(action: JobAction) {
        if (state.ciLoading) return
        val auth = state.auth ?: return
        val project = state.selectedProject ?: return
        val job = state.selectedJob ?: return
        if (!job.allows(action)) return
        val request = ++ciRequest
        state = state.copy(ciLoading = true)
        viewModelScope.launch {
            runCatching { api.jobAction(freshAuth(auth), project.id, job.id, action) }
                .onSuccess { updated ->
                    if (request == ciRequest) {
                        state = state.copy(ciLoading = false, selectedJob = updated)
                        openJob(updated)
                    }
                }
                .onFailure { if (request == ciRequest) state = state.copy(ciLoading = false, error = it.message) }
        }
    }

    fun backFromCi() {
        ciRequest++
        val pipeline = state.selectedPipeline
        if (state.selectedJob != null && pipeline != null) openPipeline(pipeline)
        else state = state.copy(selectedPipeline = null, selectedJob = null, ciLoading = false)
    }

    fun closeProject() {
        ciRequest++
        state = state.copy(selectedProject = null, openedFile = null, fileContent = null, selectedPipeline = null, selectedJob = null, jobs = emptyList(), jobLog = null, ciLoading = false)
    }

    fun closeFile() {
        state = state.copy(openedFile = null, fileContent = null)
    }

    fun logout() {
        ciRequest++
        tokenStore.clear()
        pendingOAuth = null
        state = AppState()
    }

    fun clearError() {
        state = state.copy(error = null)
    }
}

private data class SessionData(val auth: StoredAuth, val user: GitLabUser, val projects: List<GitLabProject>, val todos: List<GitLabTodo>)
private data class ProjectData(val issues: List<GitLabIssue>, val mergeRequests: List<GitLabMergeRequest>, val pipelines: List<GitLabPipeline>, val tree: List<GitLabTreeItem>)

enum class RootScreen { HOME, INBOX, PROJECTS, SEARCH, PROFILE }

data class AppState(
    val selectedPipeline: GitLabPipeline? = null,
    val selectedJob: GitLabJob? = null,
    val jobs: List<GitLabJob> = emptyList(),
    val jobLog: String? = null,
    val ciLoading: Boolean = false,
    val auth: StoredAuth? = null,
    val user: GitLabUser? = null,
    val projects: List<GitLabProject> = emptyList(),
    val todos: List<GitLabTodo> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<GitLabProject> = emptyList(),
    val screen: RootScreen = RootScreen.HOME,
    val selectedProject: GitLabProject? = null,
    val issues: List<GitLabIssue> = emptyList(),
    val mergeRequests: List<GitLabMergeRequest> = emptyList(),
    val pipelines: List<GitLabPipeline> = emptyList(),
    val repositoryTree: List<GitLabTreeItem> = emptyList(),
    val repositoryPath: String = "",
    val openedFile: GitLabTreeItem? = null,
    val fileContent: String? = null,
    val loading: Boolean = false,
    val error: String? = null
)
