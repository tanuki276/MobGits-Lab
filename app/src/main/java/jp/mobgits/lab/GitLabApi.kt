package jp.mobgits.lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GitLabApi {
    suspend fun currentUser(baseUrl: String, token: String): GitLabUser = withContext(Dispatchers.IO) {
        val json = JSONObject(get(baseUrl, token, "/api/v4/user"))
        GitLabUser(
            username = json.optString("username"),
            name = json.optString("name"),
            avatarUrl = json.optString("avatar_url")
        )
    }

    suspend fun projects(baseUrl: String, token: String): List<GitLabProject> = withContext(Dispatchers.IO) {
        val body = get(
            baseUrl,
            token,
            "/api/v4/projects?membership=true&simple=true&order_by=last_activity_at&sort=desc&per_page=50"
        )
        JSONArray(body).mapObjects { json ->
            GitLabProject(
                id = json.getLong("id"),
                name = json.optString("name"),
                pathWithNamespace = json.optString("path_with_namespace"),
                description = json.optString("description"),
                webUrl = json.optString("web_url"),
                stars = json.optInt("star_count"),
                lastActivityAt = json.optString("last_activity_at")
            )
        }
    }

    suspend fun issues(baseUrl: String, token: String, projectId: Long): List<GitLabIssue> = withContext(Dispatchers.IO) {
        val body = get(baseUrl, token, "/api/v4/projects/$projectId/issues?state=opened&per_page=50")
        JSONArray(body).mapObjects { json ->
            GitLabIssue(
                iid = json.getLong("iid"),
                title = json.optString("title"),
                author = json.optJSONObject("author")?.optString("username").orEmpty(),
                labels = json.optJSONArray("labels")?.toStringList().orEmpty()
            )
        }
    }

    suspend fun mergeRequests(baseUrl: String, token: String, projectId: Long): List<GitLabMergeRequest> = withContext(Dispatchers.IO) {
        val body = get(baseUrl, token, "/api/v4/projects/$projectId/merge_requests?state=opened&per_page=50")
        JSONArray(body).mapObjects { json ->
            GitLabMergeRequest(
                iid = json.getLong("iid"),
                title = json.optString("title"),
                author = json.optJSONObject("author")?.optString("username").orEmpty(),
                sourceBranch = json.optString("source_branch"),
                targetBranch = json.optString("target_branch"),
                draft = json.optBoolean("draft", json.optBoolean("work_in_progress", false))
            )
        }
    }

    suspend fun pipelines(baseUrl: String, token: String, projectId: Long): List<GitLabPipeline> = withContext(Dispatchers.IO) {
        val body = get(baseUrl, token, "/api/v4/projects/$projectId/pipelines?per_page=30")
        JSONArray(body).mapObjects { json ->
            GitLabPipeline(
                id = json.getLong("id"),
                status = json.optString("status"),
                ref = json.optString("ref"),
                webUrl = json.optString("web_url"),
                updatedAt = json.optString("updated_at")
            )
        }
    }

    private fun get(baseUrl: String, token: String, path: String): String {
        val normalized = normalizeBaseUrl(baseUrl)
        val connection = (URL(normalized + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("PRIVATE-TOKEN", token)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MobGits-Lab/0.1")
        }

        return try {
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
    buildList {
        for (index in 0 until length()) add(block(getJSONObject(index)))
    }

private fun JSONArray.toStringList(): List<String> =
    buildList {
        for (index in 0 until length()) add(optString(index))
    }

data class GitLabUser(
    val username: String,
    val name: String,
    val avatarUrl: String
)

data class GitLabProject(
    val id: Long,
    val name: String,
    val pathWithNamespace: String,
    val description: String,
    val webUrl: String,
    val stars: Int,
    val lastActivityAt: String
)

data class GitLabIssue(
    val iid: Long,
    val title: String,
    val author: String,
    val labels: List<String>
)

data class GitLabMergeRequest(
    val iid: Long,
    val title: String,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val draft: Boolean
)

data class GitLabPipeline(
    val id: Long,
    val status: String,
    val ref: String,
    val webUrl: String,
    val updatedAt: String
)

class GitLabApiException(val statusCode: Int, message: String) : Exception(message)
