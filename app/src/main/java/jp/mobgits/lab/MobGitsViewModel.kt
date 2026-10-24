package jp.mobgits.lab

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MobGitsViewModel(application: Application) : AndroidViewModel(application) {
    private val api = GitLabApi()
    private val tokenStore = TokenStore(application)

    var state by mutableStateOf(AppState())
        private set

    init {
        tokenStore.load()?.let { login(it.baseUrl, it.token, persist = false) }
    }

    fun login(baseUrl: String, token: String, persist: Boolean = true) {
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val user = api.currentUser(baseUrl, token)
                val projects = api.projects(baseUrl, token)
                Triple(user, projects, StoredAuth(baseUrl.trim().trimEnd('/'), token))
            }.onSuccess { (user, projects, auth) ->
                if (persist) tokenStore.save(auth.baseUrl, auth.token)
                state = AppState(auth = auth, user = user, projects = projects)
            }.onFailure { throwable ->
                state = state.copy(loading = false, error = throwable.message ?: "接続に失敗しました")
            }
        }
    }

    fun logout() {
        tokenStore.clear()
        state = AppState()
    }

    fun openProject(project: GitLabProject) {
        val auth = state.auth ?: return
        state = state.copy(selectedProject = project, loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                Triple(
                    api.issues(auth.baseUrl, auth.token, project.id),
                    api.mergeRequests(auth.baseUrl, auth.token, project.id),
                    api.pipelines(auth.baseUrl, auth.token, project.id)
                )
            }.onSuccess { (issues, mergeRequests, pipelines) ->
                state = state.copy(
                    loading = false,
                    issues = issues,
                    mergeRequests = mergeRequests,
                    pipelines = pipelines
                )
            }.onFailure { throwable ->
                state = state.copy(loading = false, error = throwable.message ?: "読み込みに失敗しました")
            }
        }
    }

    fun closeProject() {
        state = state.copy(selectedProject = null, issues = emptyList(), mergeRequests = emptyList(), pipelines = emptyList())
    }

    fun clearError() {
        state = state.copy(error = null)
    }
}

data class AppState(
    val auth: StoredAuth? = null,
    val user: GitLabUser? = null,
    val projects: List<GitLabProject> = emptyList(),
    val selectedProject: GitLabProject? = null,
    val issues: List<GitLabIssue> = emptyList(),
    val mergeRequests: List<GitLabMergeRequest> = emptyList(),
    val pipelines: List<GitLabPipeline> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)
