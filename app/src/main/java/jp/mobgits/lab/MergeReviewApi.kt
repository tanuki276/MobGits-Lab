package jp.mobgits.lab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MergeReviewApi(private val api: GitLabApi) {
    private fun root(projectId: Long, iid: Long) = "/api/v4/projects/$projectId/merge_requests/$iid"

    suspend fun load(auth: StoredAuth, projectId: Long, iid: Long): ReviewSnapshot = withContext(Dispatchers.IO) {
        val path = root(projectId, iid)
        val before = detail(JSONObject(api.get(auth, "$path?with_merge_status_recheck=true")))
        val diffs = pages(auth, "$path/diffs").map { json ->
            ReviewDiff(json.getString("old_path"), json.getString("new_path"), json.optString("diff"),
                json.optBoolean("new_file"), json.optBoolean("deleted_file"), json.optBoolean("renamed_file"),
                json.optBoolean("collapsed"), json.optBoolean("too_large"))
        }
        val discussions = discussions(auth, path)
        var warning: String? = null
        val approval = try {
            val json = JSONObject(api.get(auth, "$path/approvals"))
            ReviewApproval(json.optBoolean("user_can_approve"), json.optBoolean("user_has_approved"),
                json.optJSONArray("approved_by")?.objects()?.map { it.optJSONObject("user")?.optString("username").orEmpty() }.orEmpty())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            warning = "承認情報を取得できません: ${error.message}"
            null
        }
        val after = detail(JSONObject(api.get(auth, path)))
        check(before.refs == after.refs) { "差分が更新されました。再読み込みして確認してください" }
        ReviewSnapshot(after, diffs, discussions, approval, warning)
    }

    suspend fun comment(auth: StoredAuth, projectId: Long, iid: Long, position: ReviewPosition, body: String): ReviewDiscussion = withContext(Dispatchers.IO) {
        require(body.isNotBlank()) { "コメントを入力してください" }
        val path = root(projectId, iid)
        val latest = detail(JSONObject(api.get(auth, path)))
        check(latest.canSubmit && latest.refs == position.refs) { "差分またはMRの状態が変わりました。再読み込みして確認してください" }
        discussion(JSONObject(api.request(auth, "$path/discussions", "POST", position.fields() + ("body" to body))))
    }

    suspend fun act(auth: StoredAuth, projectId: Long, reviewed: ReviewDetail, action: ReviewAction) = withContext(Dispatchers.IO) {
        require(reviewed.canSubmit)
        val path = root(projectId, reviewed.iid)
        val current = detail(JSONObject(api.get(auth, path)))
        check(current.refs == reviewed.refs && current.canSubmit) { "差分またはMRの状態が変わりました。再読み込みして確認してください" }
        if (action == ReviewAction.MERGE) check(current.mergeable) { "現在マージできません: ${current.mergeStatus}" }
        api.request(auth, "$path/${if (action == ReviewAction.APPROVE) "approve" else "merge"}",
            if (action == ReviewAction.APPROVE) "POST" else "PUT", mapOf("sha" to reviewed.refs.head))
    }

    private fun pages(auth: StoredAuth, path: String): List<JSONObject> = buildList {
        var page = 1
        do {
            val batch = JSONArray(api.get(auth, "$path?per_page=100&page=$page"))
            addAll(batch.objects())
            page++
        } while (batch.length() == 100)
    }

    private fun discussions(auth: StoredAuth, path: String) = pages(auth, "$path/discussions").map(::discussion)

    private fun discussion(json: JSONObject) = ReviewDiscussion(json.getString("id"),
        json.getJSONArray("notes").objects().filterNot { it.optBoolean("system") }.map { note ->
            val pos = note.optJSONObject("position")
            val position = pos?.let {
                runCatching {
                    ReviewPosition(refs(it), it.getString("old_path"), it.getString("new_path"),
                        if (it.isNull("old_line")) null else it.getInt("old_line"),
                        if (it.isNull("new_line")) null else it.getInt("new_line"))
                }.getOrNull()
            }
            ReviewNote(note.getLong("id"), note.optJSONObject("author")?.optString("username").orEmpty(), note.optString("body"), position)
        })

    private fun refs(json: JSONObject) = DiffRefs(json.optString("base_sha"), json.optString("start_sha"), json.optString("head_sha"))
    private fun detail(json: JSONObject) = ReviewDetail(
        json.getLong("iid"), json.optString("title"), json.optString("description").takeUnless { it == "null" }.orEmpty(),
        json.optString("source_branch"), json.optString("target_branch"), json.optString("state"),
        json.optBoolean("draft", json.optBoolean("work_in_progress")), refs(json.optJSONObject("diff_refs") ?: JSONObject()),
        json.optString("detailed_merge_status").ifBlank { json.optString("merge_status") },
        json.optJSONObject("user")?.optBoolean("can_merge") ?: false)
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
