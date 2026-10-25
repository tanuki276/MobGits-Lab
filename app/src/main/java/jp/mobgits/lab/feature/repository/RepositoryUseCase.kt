package jp.mobgits.lab.feature.repository

import jp.mobgits.lab.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

data class FileChange(val path: String, val action: String, val content: String, val lastCommit: String = "")
data class Workspace(val base: String, val target: String, val branch: String = "mobgits/edit-${UUID.randomUUID().toString().take(12)}",
    val operation: String = UUID.randomUUID().toString(), val message: String = "", val changes: List<FileChange> = emptyList(),
    val commit: String = "", val attempted: Boolean = false) {
    fun json(): String = JSONObject().put("base", base).put("target", target).put("branch", branch).put("operation", operation)
        .put("message", message).put("commit", commit).put("attempted", attempted).put("changes", JSONArray().apply {
            changes.forEach { put(JSONObject().put("path", it.path).put("action", it.action).put("content", it.content).put("last", it.lastCommit)) }
        }).toString()
    companion object {
        fun parse(raw: String): Workspace = JSONObject(raw).let { j -> Workspace(j.getString("base"), j.getString("target"), j.getString("branch"),
            j.getString("operation"), j.getString("message"), j.getJSONArray("changes").objects().map { FileChange(it.getString("path"), it.getString("action"), it.getString("content"), it.getString("last")) }, j.optString("commit"), j.optBoolean("attempted")) }
    }
}
data class SourceFile(val path: String, val content: String, val lastCommit: String, val binary: Boolean)
data class RepositoryPage(val values: List<JSONObject>, val next: Int?, val offline: Boolean)

class RepositoryUseCase(private val data: Resources) {
    private fun root(project: Long) = "/api/v4/projects/$project"
    suspend fun page(account: String, project: Long, kind: String, ref: String, path: String, page: Int, search: String = ""): RepositoryPage = withContext(Dispatchers.Default) {
        require(page > 0)
        val endpoint = when (kind) {
            "tree" -> "repository/tree?ref=${query(ref)}&path=${query(path)}"
            "branches", "tags" -> "repository/$kind?search=${query(search)}"
            "history" -> "repository/commits?ref_name=${query(ref)}&path=${query(path)}"
            else -> error("Unsupported repository list")
        }
        val result = data.read(account, "${root(project)}/$endpoint&per_page=20&page=$page")
        RepositoryPage(JSONArray(result.json).objects(), result.nextPage, result.offline)
    }
    suspend fun targetBranch(account: String, project: Long, ref: String): String = withContext(Dispatchers.Default) {
        if (branch(account, project, ref) != null) ref
        else JSONObject(data.read(account, root(project), false).json).getString("default_branch")
    }
    suspend fun resolve(account: String, project: Long, ref: String): String = withContext(Dispatchers.Default) {
        JSONObject(data.read(account, "${root(project)}/repository/commits/${query(ref)}", false).json).getString("id")
    }
    suspend fun file(account: String, project: Long, ref: String, path: String): SourceFile = withContext(Dispatchers.Default) {
        val j = JSONObject(data.read(account, "${root(project)}/repository/files/${query(path)}?ref=${query(ref)}", false).json)
        require(j.getLong("size") <= 256 * 1024) { "256KBを超えるファイルは編集できません。" }
        val bytes = Base64.getMimeDecoder().decode(j.getString("content"))
        val text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        SourceFile(path, text.orEmpty(), j.getString("last_commit_id"), text == null || '\u0000' in text)
    }
    suspend fun commitDetail(account: String, project: Long, sha: String): JSONObject = withContext(Dispatchers.Default) {
        JSONObject(data.read(account, "${root(project)}/repository/commits/${query(sha)}").json)
    }
    suspend fun blame(account: String, project: Long, ref: String, path: String, start: Int): JSONArray = withContext(Dispatchers.Default) {
        JSONArray(data.read(account, "${root(project)}/repository/files/${query(path)}/blame?ref=${query(ref)}&range[start]=$start&range[end]=${start + 49}").json)
    }
    private suspend fun branch(account: String, project: Long, name: String): JSONObject? = try {
        JSONObject(data.read(account, "${root(project)}/repository/branches/${query(name)}", false).json)
    } catch (e: ApiFailure) { if (e.status == 404) null else throw e }
    suspend fun commit(account: String, project: Long, workspace: Workspace): String = withContext(Dispatchers.Default) {
        require(workspace.changes.isNotEmpty() && workspace.changes.size <= 20 && workspace.message.isNotBlank())
        require(workspace.branch.startsWith("mobgits/") && workspace.branch != workspace.target)
        require(workspace.changes.map { it.path }.distinct().size == workspace.changes.size)
        val marker = "MobGits-Operation: ${workspace.operation}"
        branch(account, project, workspace.branch)?.let { existing ->
            val commit = existing.getJSONObject("commit")
            check(commit.text("message").trimEnd().endsWith(marker) && commit.getJSONArray("parent_ids").length() == 1 && commit.getJSONArray("parent_ids").getString(0) == workspace.base) {
                "同名ブランチがあります。別の新規ブランチを使用してください。"
            }
            return@withContext commit.getString("id")
        }
        // Bind the branch to the exact reviewed snapshot; never force or write to the target branch.
        val request = JSONObject().put("branch", workspace.branch).put("start_sha", workspace.base)
            .put("commit_message", "${workspace.message}\n\n$marker").put("actions", JSONArray().apply {
                workspace.changes.forEach { change ->
                    require(change.path.isNotBlank() && !change.path.startsWith('/') && change.path.split('/').none { it == ".." || it == "." })
                    require(change.action in setOf("create", "update", "delete"))
                    put(JSONObject().put("action", change.action).put("file_path", change.path).apply {
                        if (change.action != "delete") put("content", change.content)
                        if (change.action != "create") { require(change.lastCommit.isNotBlank()); put("last_commit_id", change.lastCommit) }
                    })
                }
            })
        JSONObject(data.writeJson(account, "${root(project)}/repository/commits", request.toString())).getString("id")
    }
    suspend fun createMr(account: String, project: Long, workspace: Workspace): Long = withContext(Dispatchers.Default) {
        require(workspace.commit.isNotBlank())
        val existing = JSONArray(data.read(account, "${root(project)}/merge_requests?state=opened&source_branch=${query(workspace.branch)}&target_branch=${query(workspace.target)}&per_page=20", false).json)
        existing.objects().firstOrNull { it.optLong("source_project_id") == project && it.optLong("target_project_id") == project }?.let { return@withContext it.getLong("iid") }
        JSONObject(data.write(account, "${root(project)}/merge_requests", "POST", mapOf("source_branch" to workspace.branch, "target_branch" to workspace.target, "title" to workspace.message.lineSequence().first(), "remove_source_branch" to "true"))).getLong("iid")
    }
    suspend fun namespaces(account: String): List<JSONObject> = withContext(Dispatchers.Default) {
        JSONArray(data.read(account, "/api/v4/namespaces?per_page=100", false).json).objects()
    }
    suspend fun createProject(account: String, name: String, path: String, namespace: Long?, description: String, visibility: String): Long = withContext(Dispatchers.Default) {
        require(name.isNotBlank() && path.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")))
        require(visibility in setOf("private", "internal", "public"))
        JSONObject(data.write(account, "/api/v4/projects", "POST", mapOf("name" to name, "path" to path, "description" to description,
            "visibility" to visibility, "initialize_with_readme" to "true") + (namespace?.let { mapOf("namespace_id" to it.toString()) } ?: emptyMap()))).getLong("id")
    }
}
