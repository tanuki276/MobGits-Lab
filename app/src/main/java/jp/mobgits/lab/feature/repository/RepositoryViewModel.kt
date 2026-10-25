package jp.mobgits.lab.feature.repository

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mobgits.lab.core.auth.EncryptedSessionStore
import jp.mobgits.lab.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*

internal class WorkspaceStore(context: Context, account: String, project: Long) {
    private val secure = EncryptedSessionStore(context)
    private val key = "workspace:$account:$project"
    private val mutex = Mutex()
    suspend fun read(): String? = withContext(Dispatchers.IO) { mutex.withLock { secure.get(key) } }
    suspend fun write(raw: String?) = withContext(Dispatchers.IO) { mutex.withLock { secure.put(key, raw) } }
}
internal class RepositoryViewModel(private val use: RepositoryUseCase, private val store: WorkspaceStore,
    private val account: String, private val project: Long, initialRef: String) : ViewModel() {
    var ref by mutableStateOf(initialRef); private set
    var path by mutableStateOf(""); private set
    var mode by mutableStateOf("tree"); private set
    var entries by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var next by mutableStateOf<Int?>(null); private set
    var offline by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var workspace by mutableStateOf<Workspace?>(null); private set
    var source by mutableStateOf<SourceFile?>(null); private set
    var buffer by mutableStateOf(""); private set
    var detail by mutableStateOf<JSONObject?>(null); private set
    var status by mutableStateOf(""); private set
    var searching by mutableStateOf(""); private set
    private var saveJob: Job? = null
    init { action {
        store.read()?.let { raw ->
            val j = JSONObject(raw)
            workspace = j.optJSONObject("workspace")?.let { Workspace.parse(it.toString()) }
            j.optJSONObject("source")?.let { source = SourceFile(it.getString("path"), it.getString("content"), it.getString("last"), false) }
            buffer = j.optString("buffer")
            workspace?.let { ref = it.target }
            if (source != null) mode = "file"
            status = "保存した作業を復元しました"
        }
        if (mode == "tree") load()
    } }
    private fun snapshot() = JSONObject().put("workspace", workspace?.let { JSONObject(it.json()) })
        .put("source", source?.let { JSONObject().put("path", it.path).put("content", it.content).put("last", it.lastCommit) })
        .put("buffer", buffer).toString()
    private suspend fun persist() { saveJob?.join(); store.write(snapshot()) }
    fun edit(value: String) {
        if (value.toByteArray().size > 256 * 1024 || workspace?.attempted == true || busy) return
        buffer = value; status = "保存中…"
        val raw = snapshot(); val previous = saveJob
        saveJob = viewModelScope.launch { previous?.join(); try { store.write(raw); status = "下書きを端末に保存しました" } catch (e: Exception) { if (e is CancellationException) throw e; error = "下書きを保存できませんでした" } }
    }
    fun message(value: String) {
        if (workspace?.attempted == true || busy) return
        workspace = workspace?.copy(message = value)
        val raw = snapshot(); val previous = saveJob
        saveJob = viewModelScope.launch { previous?.join(); try { store.write(raw) } catch (e: Exception) { if (e is CancellationException) throw e; error = "下書きを保存できませんでした" } }
    }
    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch { try { block() } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "処理に失敗しました" } finally { busy = false } }
    }
    private suspend fun load(page: Int = 1) {
        val result = use.page(account, project, mode, workspace?.base ?: ref, path, page, searching)
        entries = if (page == 1) result.values else entries + result.values
        next = result.next; offline = result.offline
    }
    fun show(kind: String, newPath: String = path) = action {
        if (source != null && buffer != source!!.content) stageInternal()
        mode = kind; path = newPath; entries = emptyList(); load()
    }
    fun search(value: String) { searching = value }
    fun refresh() = action { load() }
    fun more() = action { next?.let { load(it) } }
    fun chooseRef(value: String) = action {
        check(workspace == null) { "作業中の変更があります。コミットまたは作業を破棄してから切り替えてください。" }
        ref = value; path = ""; mode = "tree"; searching = ""; load()
    }
    fun openFile(filePath: String) = action {
        if (source != null && buffer != source!!.content) stageInternal()
        val base = workspace?.base ?: use.resolve(account, project, ref)
        val file = use.file(account, project, base, filePath)
        check(!file.binary) { "バイナリまたはUTF-8以外のファイルは編集できません。" }
        if (workspace == null) workspace = Workspace(base, use.targetBranch(account, project, ref))
        source = file; buffer = workspace!!.changes.firstOrNull { it.path == filePath }?.content ?: file.content
        mode = "file"; persist()
    }
    fun newFile(filePath: String) = action {
        require(filePath.isNotBlank() && !filePath.startsWith('/') && filePath.split('/').none { it == ".." || it == "." || it.isBlank() }) { "ファイルパスを確認してください" }
        if (source != null && buffer != source!!.content) stageInternal()
        if (workspace == null) workspace = Workspace(use.resolve(account, project, ref), use.targetBranch(account, project, ref))
        source = SourceFile(filePath, "", "", false); buffer = ""; mode = "file"; persist()
    }
    private suspend fun stageInternal(delete: Boolean = false) {
        val w = workspace ?: return; val file = source ?: return
        check(!w.attempted) { "送信済みの作業は変更できません。送信結果を確認してください。" }
        val remaining = w.changes.filterNot { it.path == file.path }
        val change = when {
            delete && file.lastCommit.isBlank() -> null
            delete -> FileChange(file.path, "delete", "", file.lastCommit)
            file.lastCommit.isBlank() -> FileChange(file.path, "create", buffer)
            buffer != file.content -> FileChange(file.path, "update", buffer, file.lastCommit)
            else -> null
        }
        val changes = remaining + listOfNotNull(change)
        require(changes.size <= 20 && changes.sumOf { it.content.toByteArray().size } <= 1024 * 1024) { "1コミットは20ファイル・合計1MBまでです" }
        workspace = w.copy(changes = changes); persist(); status = "変更一覧に保存しました"
    }
    fun stage() = action { stageInternal() }
    fun delete() = action { stageInternal(true); source = null; buffer = ""; mode = "tree"; persist(); load() }
    fun discard() = action { saveJob?.join(); workspace = null; source = null; buffer = ""; store.write(null); mode = "tree"; load() }
    fun commit() = action {
        if (workspace?.attempted != true) stageInternal()
        var w = workspace ?: error("変更がありません")
        check(w.message.isNotBlank() && w.changes.isNotEmpty()) { "変更とコミットメッセージが必要です" }
        w = w.copy(attempted = true); workspace = w; persist()
        val sha = use.commit(account, project, w)
        workspace = w.copy(commit = sha); persist(); status = "コミット済み：MRを作成できます"
    }
    fun createMr(open: (Long) -> Unit) = action {
        val w = workspace ?: return@action
        val iid = use.createMr(account, project, w)
        saveJob?.join(); store.write(null); workspace = null; source = null; buffer = ""; open(iid)
    }
    fun commitDetail(sha: String) = action { detail = use.commitDetail(account, project, sha); mode = "commit" }
    fun blame(start: Int = 1) = action {
        val file = source ?: return@action
        entries = use.blame(account, project, workspace?.base ?: ref, file.path, start).objects()
        next = if (entries.sumOf { it.getJSONArray("lines").length() } == 50) start + 50 else null
        mode = "blame"
    }
    fun returnToFile() { mode = "file" }
}
