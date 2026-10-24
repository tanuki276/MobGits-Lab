package jp.mobgits.lab.core

import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.repository.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class RepositoryUseCaseTest {
    private class Fake : Resources {
        val responses = ArrayDeque<Any>()
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        override suspend fun read(account: String, path: String, cached: Boolean): Resource {
            assertEquals("account", account); reads += path
            return when(val next = responses.removeFirst()) { is Exception -> throw next; else -> Resource(next.toString(), false) }
        }
        override suspend fun write(account: String, path: String, method: String, fields: Map<String, String>): String {
            assertEquals("account", account); writes += path to JSONObject(fields).toString(); return "{\"iid\":7,\"id\":9}"
        }
        override suspend fun writeJson(account: String, path: String, json: String): String {
            assertEquals("account", account); writes += path to json; return "{\"id\":\"commit-sha\"}"
        }
        override suspend fun log(account: String, project: Long, job: Long, offset: Long) = LogChunk("", offset, true)
        override suspend fun clear(account: String) { }
    }
    private fun workspace() = Workspace("base-sha", "main", "mobgits/edit-unit", "operation", "日本語で変更",
        listOf(FileChange("src/a.kt", "update", "val a = 2", "last-sha"), FileChange("new.txt", "create", "new"), FileChange("old.txt", "delete", "", "old-sha")))
    @Test fun multiFileCommitPinsBaseAndIncludesConcurrencyGuards() = runBlocking {
        val fake = Fake().apply { responses.add(ApiFailure(FailureKind.NOT_FOUND, 404)) }
        assertEquals("commit-sha", RepositoryUseCase(fake).commit("account", 4, workspace()))
        val json = JSONObject(fake.writes.single().second)
        assertEquals("base-sha", json.getString("start_sha")); assertFalse(json.has("force"))
        assertEquals("mobgits/edit-unit", json.getString("branch"))
        val actions = json.getJSONArray("actions")
        assertEquals(3, actions.length()); assertEquals("last-sha", actions.getJSONObject(0).getString("last_commit_id"))
        assertFalse(actions.getJSONObject(2).has("content"))
    }
    @Test fun lostCommitResponseRecoversWithoutDuplicateWrite() = runBlocking {
        val w = workspace()
        val fake = Fake().apply { responses.add(JSONObject().put("commit", JSONObject().put("id", "recovered").put("message", "${w.message}\n\nMobGits-Operation: ${w.operation}\n").put("parent_ids", JSONArray().put(w.base))).toString()) }
        assertEquals("recovered", RepositoryUseCase(fake).commit("account", 4, w)); assertTrue(fake.writes.isEmpty())
    }
    @Test fun unrelatedExistingBranchCannotBeOverwritten() = runBlocking {
        val fake = Fake().apply { responses.add("""{"commit":{"id":"other","message":"other","parent_ids":["base-sha"]}}""") }
        try { RepositoryUseCase(fake).commit("account", 4, workspace()); fail() } catch (_: IllegalStateException) { }
        assertTrue(fake.writes.isEmpty())
    }
    @Test fun directTargetBranchAndTraversalAreRejected() = runBlocking {
        val fake = Fake().apply { responses.add(ApiFailure(FailureKind.NOT_FOUND, 404)) }
        try { RepositoryUseCase(fake).commit("account", 4, workspace().copy(branch = "main")); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(fake.reads.isEmpty())
        try { RepositoryUseCase(fake).commit("account", 4, workspace().copy(changes = listOf(FileChange("../secret", "create", "x")))); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(fake.writes.isEmpty())
    }
    @Test fun lostMrResponseFindsExistingMr() = runBlocking {
        val fake = Fake().apply { responses.add("""[{"iid":12,"source_project_id":4,"target_project_id":4}]""") }
        assertEquals(12L, RepositoryUseCase(fake).createMr("account", 4, workspace().copy(commit = "sha")))
        assertTrue(fake.writes.isEmpty())
    }
    @Test fun pendingOperationAndDraftSurviveSerialization() {
        val w = workspace().copy(attempted = true, commit = "sha")
        assertEquals(w, Workspace.parse(w.json()))
    }
    @Test fun branchPagingFetchesOnlyRequestedPageAndEncodesSearch() = runBlocking {
        val fake = Fake().apply { responses.add("[]") }
        RepositoryUseCase(fake).page("account", 4, "branches", "main", "", 2, "feature/a")
        assertEquals(1, fake.reads.size); assertTrue(fake.reads.single().contains("search=feature%2Fa&per_page=20&page=2"))
    }
}
