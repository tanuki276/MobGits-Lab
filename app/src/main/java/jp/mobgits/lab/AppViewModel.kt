package jp.mobgits.lab

import android.app.Application
import androidx.lifecycle.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import jp.mobgits.lab.core.AppGraph
import jp.mobgits.lab.core.auth.SessionStatus
import jp.mobgits.lab.core.datastore.DisplaySettings
import jp.mobgits.lab.core.model.*
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

data class UiState(
    val directory: AccountDirectory = AccountDirectory(), val settings: DisplaySettings = DisplaySettings(),
    val session: SessionStatus = SessionStatus.SignedOut, val route: Destination = Destination(),
    val entries: List<JSONObject> = emptyList(), val detail: JSONObject? = null, val approval: JSONObject? = null,
    val panel: String = "overview", val page: Int = 1, val next: Int? = null, val filter: ListFilter = ListFilter(),
    val loading: Boolean = true, val submitting: Boolean = false, val offline: Boolean = false,
    val error: String? = null, val notice: String? = null, val file: String? = null,
    val log: List<String> = emptyList(), val logOffset: Long = 0, val logEnd: Boolean = false,
    val completedDraft: String? = null, val needsRefresh: Boolean = false, val apiStatus: String = "未確認"
)

class AppViewModel @JvmOverloads constructor(app: Application, private val saved: SavedStateHandle,
    private val use: CollaborationUseCase = CollaborationUseCase(AppGraph.get(app).resources)) : AndroidViewModel(app) {
    val graph = AppGraph.get(app)
    var state by androidx.compose.runtime.mutableStateOf(UiState(route = saved.get<String>("route")?.let { runCatching { Destination.decode(it) }.getOrNull() } ?: Destination()))
        private set
    private var epoch = 0
    private var loadJob: Job? = null
    private val history = saved.get<ArrayList<String>>("history") ?: arrayListOf()
    init {
        viewModelScope.launch { graph.preferences.directory.collect { state = state.copy(directory = it) } }
        viewModelScope.launch { graph.preferences.settings.collect { state = state.copy(settings = it) } }
        viewModelScope.launch { graph.sessions.status.collect { state = state.copy(session = it) } }
        viewModelScope.launch {
            try { graph.initialize(); jp.mobgits.lab.feature.inbox.Notifications.schedule(getApplication<Application>(), graph.preferences.settings.first().enabled); state = state.copy(directory = graph.preferences.snapshot()); reload() }
            catch (e: Exception) { if (e is CancellationException) throw e; state = state.copy(loading = false, error = e.message) }
        }
    }
    private fun account() = state.directory.activeAccount?.id ?: error("アカウントを選択してください")
    fun navigate(route: Destination, root: Boolean = false) {
        if (state.submitting) return
        if (root) history.clear() else history.add(state.route.encode())
        saved["history"] = history; saved["route"] = route.encode()
        state = state.copy(route = route, detail = null, entries = emptyList(), panel = "overview", page = 1, next = null,
            error = null, notice = null, filter = ListFilter(), file = null, log = emptyList(), logOffset = 0, logEnd = false)
        reload()
    }
    fun back() {
        if (state.submitting) return
        val target = if (history.isEmpty()) Destination() else Destination.decode(history.removeAt(history.lastIndex))
        saved["history"] = history
        val remaining = ArrayList(history)
        navigate(target, root = true)
        history.addAll(remaining); saved["history"] = history
    }
    fun filter(value: ListFilter) { state = state.copy(filter = value, page = 1, entries = emptyList()); reload() }
    fun panel(value: String) { state = state.copy(panel = value, entries = emptyList(), page = 1, next = null); reload() }
    fun more() { state.next?.let { state = state.copy(page = it); reload(append = state.panel != "diffs") } }
    fun jumpDiff(page: Int) { if (page > 0 && page <= 10000 && !state.loading) { state = state.copy(page = page, entries = emptyList()); reload() } }
    fun previousDiff() { if (state.page > 1) { state = state.copy(page = state.page - 1, entries = emptyList()); reload() } }
    fun reload(append: Boolean = false) {
        val account = state.directory.activeAccount?.id
        val d = state.route
        val current = ++epoch; loadJob?.cancel()
        if (account == null || d.kind == "settings") { state = state.copy(loading = false); return }
        state = state.copy(loading = true, error = null)
        val start = state
        loadJob = viewModelScope.launch {
            try {
                var result = start
                when {
                    d.kind == "file" -> { val value = use.file(account, d); result = result.copy(file = value.json, offline = value.offline) }
                    d.kind in setOf("project", "issue", "mr", "pipeline", "job") -> {
                        val detail = use.detail(account, d)
                        result = result.copy(detail = detail.value, offline = detail.offline)
                        if (d.kind == "mr") {
                            try { result = result.copy(approval = use.approval(account, d).value) }
                            catch (e: Exception) { if (e is CancellationException) throw e; result = result.copy(approval = null) }
                        }
                        if (start.panel !in setOf("overview", "logs")) {
                            val listRoute = d.copy(kind = start.panel, path = d.kind)
                            val values = use.list(account, listRoute, start.page, start.filter)
                            if (start.panel == "diffs") {
                                val refreshed = use.detail(account, d)
                                check(use.refs(detail.value) == use.refs(refreshed.value)) { "差分が更新されました。再読み込みしてください" }
                            }
                            result = result.copy(entries = if (append) start.entries + values.entries else values.entries, next = values.next, offline = detail.offline || values.offline)
                        }
                    }
                    else -> {
                        val values = use.list(account, d, start.page, start.filter)
                        result = result.copy(entries = if (append) start.entries + values.entries else values.entries, next = values.next, offline = values.offline)
                    }
                }
                if (current == epoch) state = result.copy(directory = state.directory, settings = state.settings, session = state.session,
                    loading = false, needsRefresh = false, apiStatus = if (result.offline) "オフライン（キャッシュ）" else "接続成功")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == epoch) state = state.copy(loading = false, error = e.message, apiStatus = "接続失敗") }
        }
    }
    private fun mutate(message: String, action: suspend (String, Destination) -> Unit) {
        if (state.submitting || state.offline || state.loading || state.needsRefresh) return
        val id = runCatching { account() }.getOrElse { return }
        val d = state.route; val current = ++epoch; loadJob?.cancel()
        state = state.copy(submitting = true, error = null, notice = null, completedDraft = null)
        viewModelScope.launch {
            try { action(id, d); if (current == epoch) { state = state.copy(submitting = false, notice = message); reload() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == epoch) state = state.copy(submitting = false, needsRefresh = true, error = "${e.message}。再読み込みして反映状態を確認してください") }
        }
    }
    fun save(fields: Map<String, String>, create: Boolean = false) = mutate("保存しました") { id, d -> use.save(id, d, fields, create); Unit }
    fun draftKey(thread: String?, position: ReviewPosition?) = commentDraftKey(state.directory.activeAccount?.id, state.route, thread, position)
    fun draft(key: String): String = saved[key] ?: ""
    fun setDraft(key: String, body: String) { saved[key] = body }
    fun comment(body: String, thread: String? = null, position: ReviewPosition? = null) {
        val key = draftKey(thread, position)
        mutate("コメントを投稿しました") { id, d ->
            use.comment(id, d, body, thread, position)
            saved[key] = ""; state = state.copy(completedDraft = key)
        }
    }
    fun resolve(thread: String, value: Boolean) = mutate("スレッドを更新しました") { id, d -> use.resolve(id, d, thread, value) }
    fun review(action: String, refs: DiffRefs, approvalPassword: String? = null) = mutate("操作が完了しました") { id, d -> use.review(id, d, refs, action, approvalPassword) }
    fun jobAction(action: String) = mutate("ジョブを更新しました") { id, d ->
        val result = use.jobAction(id, d, action)
        if (d.kind == "job" && action == "retry") {
            val next = d.copy(id = result.getLong("id")); saved["route"] = next.encode()
            state = state.copy(route = next, log = emptyList(), logOffset = 0, logEnd = false)
        }
    }
    fun done(todo: Long) = mutate("To-Doを完了しました") { id, _ -> use.done(id, todo) }
    fun resetLog() { state = state.copy(log = emptyList(), logOffset = 0, logEnd = false); loadLog() }
    fun loadLog() {
        if (state.loading || state.submitting) return
        val id = runCatching { account() }.getOrElse { return }; val d = state.route; val current = epoch
        val offset = state.logOffset; state = state.copy(loading = true)
        viewModelScope.launch {
            try {
                val chunk = use.log(id, d, offset)
                if (current == epoch) state = state.copy(loading = false, log = (if (chunk.text.isNotEmpty()) state.log + chunk.text else state.log).takeLast(32), logOffset = chunk.nextOffset, logEnd = chunk.end)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == epoch) state = state.copy(loading = false, error = e.message) }
        }
    }
    private fun resetAccountScope() {
        epoch++; loadJob?.cancel(); history.clear(); saved["history"] = history
        saved["route"] = Destination().encode()
        state = UiState(directory = state.directory, settings = state.settings, session = state.session, loading = true)
    }
    fun login(serverId: String, open: (String) -> Unit) = authOperation { open(graph.sessions.beginLogin(serverId)) }
    fun callback(uri: String) = authOperation { resetAccountScope(); graph.sessions.completeLogin(uri); state = state.copy(directory = graph.preferences.snapshot()); navigate(Destination(), true) }
    fun switch(id: String) = authOperation { resetAccountScope(); graph.sessions.switchAccount(id); state = state.copy(directory = graph.preferences.snapshot()); navigate(Destination(), true) }
    fun logout(id: String) = authOperation { resetAccountScope(); graph.sessions.signOut(id); graph.resources.clear(id); state = state.copy(directory = graph.preferences.snapshot()); navigate(Destination(), true) }
    fun addServer(url: String, client: String, name: String) = authOperation {
        val value = GitLabServer.create(url, client, name)
        val previous = graph.preferences.snapshot().servers.find { it.id == value.id }
        graph.preferences.saveServer(if (client.isBlank() && previous != null) value.copy(oauthClientId = previous.oauthClientId) else value)
        state = state.copy(directory = graph.preferences.snapshot())
    }
    fun flag(key: String, value: Boolean) = authOperation { graph.preferences.setFlag(key, value) }
    fun theme(value: String) = authOperation { graph.preferences.theme(value) }
    fun checkApi() = authOperation {
        state = state.copy(apiStatus = "確認中")
        try { use.checkApi(account()); state = state.copy(apiStatus = "接続成功") }
        catch (e: Exception) { state = state.copy(apiStatus = "接続失敗"); throw e }
    }
    fun clearCache() = authOperation { state.directory.activeAccount?.let { graph.resources.clear(it.id) }; state = state.copy(notice = "ローカルキャッシュを削除しました") }
    private fun authOperation(action: suspend () -> Unit) {
        viewModelScope.launch { try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { state = state.copy(loading = false, error = e.message) } }
    }
    fun deepLink(account: String, target: Destination) = authOperation {
        require(graph.preferences.snapshot().accounts.any { it.id == account }) { "通知のアカウントを追加し直してください" }
        resetAccountScope(); graph.sessions.switchAccount(account)
        state = state.copy(directory = graph.preferences.snapshot()); navigate(target, true)
    }
    suspend fun options(kind: String, page: Int, search: String) = use.list(account(), state.route.copy(kind = kind), page, ListFilter(search = search))
    fun showError(message: String) { state = state.copy(error = message) }
    fun refs() = state.detail?.let(use::refs) ?: DiffRefs("", "", "")
}
