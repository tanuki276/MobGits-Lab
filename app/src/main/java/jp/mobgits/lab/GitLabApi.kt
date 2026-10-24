package jp.mobgits.lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

open class GitLabApi {
    suspend fun currentUser(auth: StoredAuth): GitLabUser = withContext(Dispatchers.IO) {
        val json = JSONObject(get(auth, "/api/v4/user"))
        GitLabUser(
            username = json.optString("username"),
            name = json.optString("name"),
            avatarUrl = json.optString("avatar_url"),
            webUrl = json.optString("web_url")
        )
    }

    suspend fun projects(auth: StoredAuth): List<GitLabProject> = withContext(Dispatchers.IO) {
        val body = get(auth, "/api/v4/projects?membership=true&simple=true&order_by=last_activity_at&sort=desc&per_page=50")
        JSONArray(body).mapObjects(::projectFromJson)
    }

    suspend fun searchProjects(auth: StoredAuth, query: String): List<GitLabProject> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        JSONArray(get(auth, "/api/v4/projects?search=$q&simple=true&order_by=last_activity_at&sort=desc&per_page=30"))
            .mapObjects(::projectFromJson)
    }

    suspend fun todos(auth: StoredAuth): List<GitLabTodo> = withContext(Dispatchers.IO) {
        JSONArray(get(auth, "/api/v4/todos?state=pending&per_page=50")).mapObjects { json ->
            val target = json.optJSONObject("target")
            val project = json.optJSONObject("project")
            GitLabTodo(
                id = json.getLong("id"),
                actionName = json.optString("action_name"),
                targetType = json.optString("target_type"),
                title = target?.optString("title").orEmpty().ifBlank { project?.optString("name").orEmpty() },
                body = json.optString("body"),
                projectName = project?.optString("path_with_namespace").orEmpty(),
                createdAt = json.optString("created_at")
            )
        }
    }

    suspend fun markTodoDone(auth: StoredAuth, id: Long) = withContext(Dispatchers.IO) {
        request(auth, "/api/v4/todos/$id/mark_as_done", "POST")
    }

    suspend fun issues(auth: StoredAuth, projectId: Long): List<GitLabIssue> = withContext(Dispatchers.IO) {
        JSONArray(get(auth, "/api/v4/projects/$projectId/issues?state=opened&per_page=50")).mapObjects { json ->
            GitLabIssue(
                iid = json.getLong("iid"),
                title = json.optString("title"),
                author = json.optJSONObject("author")?.optString("username").orEmpty(),
                labels = json.optJSONArray("labels")?.toStringList().orEmpty(),
                description = json.optString("description"),
                webUrl = json.optString("web_url")
            )
        }
    }

    suspend fun mergeRequests(auth: StoredAuth, projectId: Long): List<GitLabMergeRequest> = withContext(Dispatchers.IO) {
        JSONArray(get(auth, "/api/v4/projects/$projectId/merge_requests?state=opened&per_page=50")).mapObjects { json ->
            GitLabMergeRequest(
                iid = json.getLong("iid"),
                title = json.optString("title"),
                author = json.optJSONObject("author")?.optString("username").orEmpty(),
                sourceBranch = json.optString("source_branch"),
                targetBranch = json.optString("target_branch"),
                draft = json.optBoolean("draft", json.optBoolean("work_in_progress", false)),
                description = json.optString("description"),
                webUrl = json.optString("web_url")
            )
        }
    }

    suspend fun pipelines(auth: StoredAuth, projectId: Long): List<GitLabPipeline> = withContext(Dispatchers.IO) {
        JSONArray(get(auth, "/api/v4/projects/$projectId/pipelines?per_page=30")).mapObjects { json ->
            GitLabPipeline(
                id = json.getLong("id"),
                status = json.optString("status"),
                ref = json.optString("ref"),
                webUrl = json.optString("web_url"),
                updatedAt = json.optString("updated_at")
            )
        }
    }

    suspend fun pipelineJobs(auth: StoredAuth, projectId: Long, pipelineId: Long): List<GitLabJob> = withContext(Dispatchers.IO) {
        buildList {
            var page = 1
            do {
                val batch = JSONArray(get(auth, "/api/v4/projects/$projectId/pipelines/$pipelineId/jobs?per_page=100&page=$page"))
                addAll(batch.mapObjects { jobFromJson(it) })
                page++
            } while (batch.length() == 100)
        }
    }

    suspend fun jobTrace(auth: StoredAuth, projectId: Long, jobId: Long): String = withContext(Dispatchers.IO) {
        get(auth, "/api/v4/projects/$projectId/jobs/$jobId/trace")
    }

    suspend fun jobAction(auth: StoredAuth, projectId: Long, jobId: Long, action: JobAction): GitLabJob = withContext(Dispatchers.IO) {
        jobFromJson(JSONObject(request(auth, "/api/v4/projects/$projectId/jobs/$jobId/${action.path}", "POST")))
    }

    private fun jobFromJson(json: JSONObject) = GitLabJob(
        json.getLong("id"), json.optString("name"), json.optString("stage"), json.optString("status")
    )

    suspend fun repositoryTree(auth: StoredAuth, projectId: Long, path: String = "", ref: String? = null): List<GitLabTreeItem> = withContext(Dispatchers.IO) {
        val encodedPath = URLEncoder.encode(path, Charsets.UTF_8.name())
        val refPart = ref?.takeIf { it.isNotBlank() }?.let { "&ref=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
        JSONArray(get(auth, "/api/v4/projects/$projectId/repository/tree?path=$encodedPath&per_page=100$refPart")).mapObjects { json ->
            GitLabTreeItem(
                id = json.optString("id"),
                name = json.optString("name"),
                path = json.optString("path"),
                type = json.optString("type")
            )
        }.sortedWith(compareBy<GitLabTreeItem> { it.type != "tree" }.thenBy { it.name.lowercase() })
    }

    suspend fun rawFile(auth: StoredAuth, projectId: Long, filePath: String, ref: String): String = withContext(Dispatchers.IO) {
        val path = URLEncoder.encode(filePath, Charsets.UTF_8.name()).replace("+", "%20")
        val encodedRef = URLEncoder.encode(ref, Charsets.UTF_8.name())
        get(auth, "/api/v4/projects/$projectId/repository/files/$path/raw?ref=$encodedRef")
    }

    private fun projectFromJson(json: JSONObject) = GitLabProject(
        id = json.getLong("id"),
        name = json.optString("name"),
        pathWithNamespace = json.optString("path_with_namespace"),
        description = json.optString("description"),
        webUrl = json.optString("web_url"),
        stars = json.optInt("star_count"),
        lastActivityAt = json.optString("last_activity_at"),
        defaultBranch = json.optString("default_branch", "main")
    )

    internal fun get(auth: StoredAuth, path: String): String = request(auth, path, "GET")

    internal open fun request(auth: StoredAuth, path: String, method: String, fields: Map<String, String> = emptyMap()): String {
        val payload = fields.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
        val normalized = normalizeBaseUrl(auth.baseUrl)
        val connection = (URL(normalized + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MobGits-Lab/0.2")
            when (auth.type) {
                AuthType.OAUTH -> setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
                AuthType.PAT -> setRequestProperty("PRIVATE-TOKEN", auth.accessToken)
            }
            if (method == "POST" || method == "PUT") {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setFixedLengthStreamingMode(payload.size)
            }
        }

        return try {
            if (method == "POST" || method == "PUT") connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("message") }.getOrNull()
                throw GitLabApiException(code, detail?.takeIf { it.isNotBlank() } ?: "GitLab API エラー")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        require(trimmed.startsWith("https://")) { "接続先URLは https:// から始めてください" }
        return trimmed
    }
}

private inline fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> =
    buildList { for (index in 0 until length()) add(block(getJSONObject(index))) }

private fun JSONArray.toStringList(): List<String> =
    buildList { for (index in 0 until length()) add(optString(index)) }

data class GitLabUser(val username: String, val name: String, val avatarUrl: String, val webUrl: String)
data class GitLabProject(
    val id: Long,
    val name: String,
    val pathWithNamespace: String,
    val description: String,
    val webUrl: String,
    val stars: Int,
    val lastActivityAt: String,
    val defaultBranch: String
)
data class GitLabIssue(val iid: Long, val title: String, val author: String, val labels: List<String>, val description: String, val webUrl: String)
data class GitLabMergeRequest(val iid: Long, val title: String, val author: String, val sourceBranch: String, val targetBranch: String, val draft: Boolean, val description: String, val webUrl: String)
data class GitLabPipeline(val id: Long, val status: String, val ref: String, val webUrl: String, val updatedAt: String)
data class GitLabTodo(val id: Long, val actionName: String, val targetType: String, val title: String, val body: String, val projectName: String, val createdAt: String)
data class GitLabTreeItem(val id: String, val name: String, val path: String, val type: String)
class GitLabApiException(val statusCode: Int, message: String) : Exception(message)
