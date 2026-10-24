package jp.mobgits.lab.feature.collaboration

import jp.mobgits.lab.*
import jp.mobgits.lab.core.network.*
import org.json.*

/** All routes carry account scope and GitLab's project-local IID, never a token. */
data class Destination(val kind: String = "home", val project: Long = 0, val id: Long = 0, val path: String = "", val ref: String = "main") {
    fun encode() = JSONObject().put("kind", kind).put("project", project).put("id", id).put("path", path).put("ref", ref).toString()
    companion object {
        fun decode(raw: String) = JSONObject(raw).let { Destination(it.getString("kind"), it.optLong("project"), it.optLong("id"), it.optString("path"), it.optString("ref", "main")) }
    }
}
data class ListFilter(val state: String = "opened", val scope: String = "all", val search: String = "", val labels: String = "", val assignee: String = "", val milestone: String = "")
data class EntityPage(val entries: List<JSONObject>, val next: Int?, val offline: Boolean)
data class Detail(val value: JSONObject, val offline: Boolean)

class CollaborationUseCase(private val data: ResourceRepository) {
    private fun root(d: Destination): String = "/api/v4/projects/${d.project}"
    fun entityPath(d: Destination): String = when (d.kind) {
        "issue" -> "${root(d)}/issues/${d.id}"
        "mr" -> "${root(d)}/merge_requests/${d.id}"
        "pipeline" -> "${root(d)}/pipelines/${d.id}"
        "job" -> "${root(d)}/jobs/${d.id}"
        "project" -> root(d)
        else -> error("Unknown detail")
    }
    suspend fun list(account: String, d: Destination, page: Int, filter: ListFilter = ListFilter()): EntityPage {
        require(page > 0)
        val endpoint = when (d.kind) {
            "home", "projects" -> "/api/v4/projects?membership=true&simple=true&order_by=last_activity_at"
            "search" -> "/api/v4/projects?search=${query(filter.search)}&simple=true"
            "inbox" -> "/api/v4/todos?state=pending"
            "issues" -> "${root(d)}/issues?state=${query(filter.state)}&scope=${query(filter.scope)}&search=${query(filter.search)}&labels=${query(filter.labels)}" +
                (if (filter.assignee.isNotBlank()) "&assignee_username[]=${query(filter.assignee)}" else "") +
                (if (filter.milestone.isNotBlank()) "&milestone=${query(filter.milestone)}" else "")
            "mrs" -> "${root(d)}/merge_requests?state=${query(filter.state)}&scope=${query(filter.scope)}&search=${query(filter.search)}"
            "pipelines" -> "${root(d)}/pipelines?order_by=id&sort=desc"
            "jobs" -> "${root(d)}/pipelines/${d.id}/jobs?include_retried=false"
            "commits" -> "${root(d)}/merge_requests/${d.id}/commits?order=default"
            "discussions" -> "${root(d)}/${if (d.path == "issue") "issues" else "merge_requests"}/${d.id}/discussions?order_by=created_at"
            "activity" -> "${root(d)}/${if (d.path == "issue") "issues" else "merge_requests"}/${d.id}/notes?sort=asc"
            "diffs" -> "${root(d)}/merge_requests/${d.id}/diffs?unidiff=true"
            "tree" -> "${root(d)}/repository/tree?path=${query(d.path)}&ref=${query(d.ref)}"
            "members" -> "${root(d)}/members/all?query=${query(filter.search)}"
            "labels" -> "${root(d)}/labels?search=${query(filter.search)}"
            "milestones" -> "${root(d)}/milestones?state=active"
            else -> error("Unknown list")
        }
        val response = data.read(account, "$endpoint&per_page=20&page=$page")
        val values = JSONArray(response.json).objects()
        return EntityPage(values, response.nextPage ?: if (values.size == 20) page + 1 else null, response.offline)
    }
    suspend fun detail(account: String, d: Destination) = data.read(account, entityPath(d)).let { Detail(JSONObject(it.json), it.offline) }
    suspend fun file(account: String, d: Destination) = data.read(account, "${root(d)}/repository/files/${query(d.path)}/raw?ref=${query(d.ref)}")
    suspend fun save(account: String, d: Destination, fields: Map<String, String>, create: Boolean = false): JSONObject {
        val path = if (create) "${root(d)}/issues" else entityPath(d)
        return JSONObject(data.write(account, path, if (create) "POST" else "PUT", fields))
    }
    suspend fun comment(account: String, d: Destination, body: String, discussionId: String? = null, position: ReviewPosition? = null) {
        require(body.isNotBlank())
        val base = entityPath(d)
        val path = if (discussionId == null) "$base/discussions" else "$base/discussions/${query(discussionId)}/notes"
        if (position != null) {
            val current = detail(account, d)
            check(!current.offline && refs(current.value) == position.refs) { "差分が更新されました。読み直してください" }
        }
        data.write(account, path, "POST", mapOf("body" to body) + position?.fields().orEmpty())
    }
    suspend fun resolve(account: String, d: Destination, discussion: String, resolved: Boolean) {
        data.write(account, "${entityPath(d)}/discussions/${query(discussion)}", "PUT", mapOf("resolved" to resolved.toString()))
    }
    suspend fun approval(account: String, d: Destination) = data.read(account, "${entityPath(d)}/approvals").let { Detail(JSONObject(it.json), it.offline) }
    suspend fun review(account: String, d: Destination, reviewed: DiffRefs, action: String) {
        require(action in setOf("approve", "unapprove", "merge"))
        val latest = detail(account, d)
        check(!latest.offline && reviewed.complete && refs(latest.value) == reviewed) { "差分が更新されました。読み直してください" }
        check(latest.value.text("state") == "opened") { "このMRは開いていません" }
        if (action == "merge") check(!latest.value.optBoolean("draft") && latest.value.text("detailed_merge_status") == "mergeable") { "マージ条件が揃っていません" }
        data.write(account, "${entityPath(d)}/$action", if (action == "merge") "PUT" else "POST", mapOf("sha" to reviewed.head))
    }
    suspend fun jobAction(account: String, d: Destination, action: String) {
        require(action in setOf("retry", "cancel"))
        data.write(account, "${entityPath(d)}/$action", "POST")
    }
    suspend fun log(account: String, d: Destination, offset: Long) = data.log(account, d.project, d.id, offset)
    suspend fun done(account: String, id: Long) { data.write(account, "/api/v4/todos/$id/mark_as_done", "POST") }
    fun refs(json: JSONObject): DiffRefs = json.optJSONObject("diff_refs").let { DiffRefs(it?.text("base_sha").orEmpty(), it?.text("start_sha").orEmpty(), it?.text("head_sha").orEmpty()) }
}
