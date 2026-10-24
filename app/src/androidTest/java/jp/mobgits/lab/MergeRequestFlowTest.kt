package jp.mobgits.lab

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.core.AppGraph
import jp.mobgits.lab.core.model.*
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class MergeRequestFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun diffInlineCommentApproveAndMergeUseReviewedSha() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val graph = AppGraph.get(app)
        val server = GitLabServer.create("https://mr-test.example", "test-client")
        val account = GitLabAccount.create(server.id, 7, "reviewer", "Reviewer", isActive = true)
        runBlocking { graph.preferences.saveServer(server); graph.preferences.saveAccount(account) }
        val fake = FakeResources(); val store = ViewModelStore(); lateinit var vm: AppViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = AppViewModel(app, SavedStateHandle(mapOf("route" to Destination("mr", 42, 7).encode())), CollaborationUseCase(fake)); store.put("mr", vm)
        }
        try {
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { MobGitsShell(vm, {}, {}) } }
            compose.waitUntil(15000) { vm.state.detail != null && !vm.state.loading }
            compose.onNodeWithText("差分").performClick()
            compose.waitUntil(15000) { vm.state.entries.isNotEmpty() && !vm.state.loading }
            compose.waitUntil(15000) { compose.onAllNodesWithText("+new").fetchSemanticsNodes().isNotEmpty() }
            compose.saveScreenshot("mr-diff-dark.png")
            compose.onNodeWithText("+new").performClick()
            compose.onNodeWithTag("comment-body").performTextInput("Please fix")
            compose.onNodeWithText("投稿").performClick()
            compose.waitUntil(15000) { fake.commented && !vm.state.submitting && !vm.state.loading }
            compose.onNodeWithText("概要").performClick()
            compose.waitUntil(15000) { !vm.state.loading }
            compose.onNodeWithTag("entity-detail-list").performScrollToNode(hasText("Approve"))
            compose.onNodeWithText("Approve").performClick()
            compose.onAllNodesWithText("Approve").onLast().performClick()
            compose.waitUntil(15000) { fake.approved && !vm.state.loading && !vm.state.submitting }
            compose.onNodeWithTag("entity-detail-list").performScrollToNode(hasText("Merge"))
            compose.onNodeWithText("Merge").performClick()
            compose.onAllNodesWithText("Merge").onLast().performClick()
            compose.waitUntil(15000) { fake.merged && !vm.state.loading && !vm.state.submitting }
            assertTrue(fake.scopes.all { it == account.id })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { graph.preferences.remove(account.id) }
        }
    }
    private class FakeResources : Resources {
        @Volatile var commented = false; @Volatile var approved = false; @Volatile var merged = false
        val scopes = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun read(account: String, path: String, cached: Boolean): Resource {
            scopes += account
            val json = when {
                path.contains("/approvals") -> """{"user_can_approve":true,"user_has_approved":$approved,"approved_by":[]}"""
                path.contains("/diffs?") -> """[{"old_path":"a.kt","new_path":"a.kt","diff":"@@ -1 +1 @@\n-old\n+new"}]"""
                path.contains("/discussions?") -> "[]"
                else -> """{"iid":7,"title":"Review MR","state":"${if (merged) "merged" else "opened"}","detailed_merge_status":"mergeable","source_branch":"feature","target_branch":"main","user":{"can_merge":true},"diff_refs":{"base_sha":"base","start_sha":"start","head_sha":"head"}}"""
            }
            return Resource(json, false)
        }
        override suspend fun write(account: String, path: String, method: String, fields: Map<String, String>): String {
            scopes += account
            when {
                path.endsWith("/discussions") -> { assertEquals("1", fields["position[new_line]"]); assertEquals("head", fields["position[head_sha]"]); commented = true }
                path.endsWith("/approve") -> { assertEquals("head", fields["sha"]); approved = true }
                path.endsWith("/merge") -> { assertEquals("PUT", method); assertEquals("head", fields["sha"]); merged = true }
            }
            return "{}"
        }
        override suspend fun log(account: String, project: Long, job: Long, offset: Long) = LogChunk("", offset, true)
        override suspend fun clear(account: String) { }
    }
}
