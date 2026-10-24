package jp.mobgits.lab

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.core.AppGraph
import jp.mobgits.lab.core.model.*
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class IssueFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun issueCreateCommentEditAndCloseUseOneAccountScope() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val graph = AppGraph.get(app)
        val server = GitLabServer.create("https://test.example", "test-client")
        val account = GitLabAccount.create(server.id, 100, "tester", "Tester", isActive = true)
        runBlocking { graph.preferences.saveServer(server); graph.preferences.saveAccount(account) }
        val fake = FakeResources()
        lateinit var vm: AppViewModel
        val store = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = AppViewModel(app, SavedStateHandle(mapOf("route" to Destination("issues", 42).encode())), CollaborationUseCase(fake))
            store.put("test", vm)
        }
        try {
            compose.setContent { MaterialTheme { MobGitsShell(vm, {}, {}) } }
            compose.waitUntil(15000) { !vm.state.loading }
            compose.onNodeWithText("＋ Issueを作成").performClick()
            compose.onNodeWithTag("issue-title").performTextInput("Created issue")
            compose.onNodeWithTag("issue-description").performTextInput("**Markdown**")
            compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
            compose.waitUntil(15000) { fake.created && !vm.state.loading && !vm.state.submitting }
            compose.onNodeWithText("Created issue").performClick()
            compose.waitUntil(15000) { vm.state.detail != null && !vm.state.loading }
            compose.onNodeWithTag("entity-detail-list").performScrollToNode(hasTestTag("comment-body"))
            compose.onNodeWithTag("comment-body").performTextInput("Review comment")
            compose.onNodeWithText("投稿").performScrollTo().performClick()
            compose.waitUntil(15000) { fake.commented && !vm.state.loading && !vm.state.submitting }
            compose.onNodeWithTag("entity-detail-list").performScrollToNode(hasText("編集"))
            compose.onNodeWithText("編集").performClick()
            compose.onNodeWithTag("issue-title").performTextReplacement("Edited issue")
            compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
            compose.waitUntil(15000) { fake.title == "Edited issue" && !vm.state.loading && !vm.state.submitting }
            compose.onNodeWithTag("entity-detail-list").performScrollToNode(hasText("Close"))
            compose.onNodeWithText("Close").performClick()
            compose.onAllNodesWithText("Close").onLast().performClick()
            compose.waitUntil(15000) { fake.closed && !vm.state.loading && !vm.state.submitting }
            assertTrue(fake.accounts.all { it == account.id })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { graph.preferences.remove(account.id) }
        }
    }
    private class FakeResources : Resources {
        @Volatile var created = false
        @Volatile var commented = false
        @Volatile var closed = false
        @Volatile var title = "Created issue"
        val accounts = java.util.Collections.synchronizedList(mutableListOf<String>())
        private fun issue() = JSONObject().put("id", 900).put("iid", 1).put("title", title).put("description", "**Markdown**")
            .put("state", if (closed) "closed" else "opened").toString()
        override suspend fun read(account: String, path: String, cached: Boolean): Resource {
            accounts += account
            return Resource(if (path.contains("/issues/1")) issue() else if (created) "[${issue()}]" else "[]", false)
        }
        override suspend fun write(account: String, path: String, method: String, fields: Map<String, String>): String {
            accounts += account
            when { path.endsWith("/discussions") -> commented = true
                method == "POST" -> created = true
                fields["state_event"] == "close" -> closed = true
                fields.containsKey("title") -> title = fields.getValue("title") }
            return issue()
        }
        override suspend fun log(account: String, project: Long, job: Long, offset: Long) = LogChunk("", offset, true)
        override suspend fun clear(account: String) { }
    }
}
