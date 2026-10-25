package jp.mobgits.lab

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.repository.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class RepositoryFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun editConfirmCommitAndCreateMergeRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val draft = WorkspaceStore(context, "repo-flow", 42)
        runBlocking { draft.write(null) }
        val fake = Fake()
        val owner = ViewModelStore()
        lateinit var vm: RepositoryViewModel
        var opened = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = RepositoryViewModel(RepositoryUseCase(fake), draft, "repo-flow", 42, "main")
            owner.put("repo", vm)
        }
        try {
            compose.setContent { MaterialTheme { RepositoryContent(vm) { opened = it } } }
            compose.waitUntil(15000) { !vm.busy }
            compose.onNodeWithText("README.md").performClick()
            compose.waitUntil(15000) { vm.source != null && !vm.busy }
            compose.onNodeWithText("コードを編集").performScrollTo().performClick()
            compose.onNodeWithTag("repository-editor").performScrollTo().performTextReplacement("# Updated\n日本語の変更")
            compose.onNodeWithTag("repository-list").performScrollToNode(hasTestTag("commit-message"))
            compose.onNodeWithTag("commit-message").performTextInput("Update README")
            compose.onNodeWithText("コミットを確認").performScrollTo().performClick()
            compose.onNodeWithText("キャンセル").performClick()
            assertEquals(0, fake.commits)
            compose.onNodeWithText("コミットを確認").performClick()
            compose.onNodeWithText("実行").performClick()
            compose.waitUntil(15000) { vm.workspace?.commit == "new-sha" && !vm.busy }
            compose.onNodeWithText("Merge Requestを作成").performScrollTo()
            compose.saveScreenshot("repository-editing-light.png")
            compose.onNodeWithText("Merge Requestを作成").performClick()
            compose.waitUntil(15000) { opened == 8L }
            assertEquals(1, fake.commits)
            assertEquals(1, fake.mrs)
            assertTrue(fake.json!!.getString("branch").startsWith("mobgits/"))
            assertEquals("base-sha", fake.json!!.getString("start_sha"))
            assertEquals("# Updated\n日本語の変更", fake.json!!.getJSONArray("actions").getJSONObject(0).getString("content"))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
            runBlocking { draft.write(null) }
        }
    }
    private class Fake : Resources {
        @Volatile var commits = 0
        @Volatile var mrs = 0
        var json: JSONObject? = null
        override suspend fun read(account: String, path: String, cached: Boolean): Resource {
            assertEquals("repo-flow", account)
            return Resource(when {
                path.contains("/repository/tree") -> """[{"name":"README.md","path":"README.md","type":"blob"}]"""
                path.contains("/repository/files/") -> """{"size":6,"content":"IyBPbGQK","last_commit_id":"file-sha"}"""
                path.endsWith("/repository/commits/main") -> """{"id":"base-sha"}"""
                path.endsWith("/repository/branches/main") -> """{"name":"main"}"""
                path.contains("/repository/branches/") -> throw ApiFailure(FailureKind.NOT_FOUND, 404)
                path.contains("/merge_requests?") -> "[]"
                else -> error("Unexpected read: $path")
            }, false)
        }
        override suspend fun writeJson(account: String, path: String, json: String): String { commits++; this.json = JSONObject(json); return """{"id":"new-sha"}""" }
        override suspend fun write(account: String, path: String, method: String, fields: Map<String, String>): String { mrs++; return """{"iid":8}""" }
        override suspend fun log(account: String, project: Long, job: Long, offset: Long) = LogChunk("", offset, true)
        override suspend fun clear(account: String) { }
    }
}
