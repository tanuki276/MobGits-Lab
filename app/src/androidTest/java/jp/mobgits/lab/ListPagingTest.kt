package jp.mobgits.lab

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.core.AppGraph
import jp.mobgits.lab.core.model.*
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class ListPagingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pagingFailureRefreshAndOfflineAppendPreserveListSafety() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val graph = AppGraph.get(app)
        val server = GitLabServer.create("https://paging.example", "test-client")
        val account = GitLabAccount.create(server.id, 101, "paging", "Paging", isActive = true)
        runBlocking { graph.preferences.saveServer(server); graph.preferences.saveAccount(account) }
        val fake = PagingResources()
        lateinit var vm: AppViewModel
        val store = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = AppViewModel(app, SavedStateHandle(), CollaborationUseCase(fake))
            store.put("paging", vm)
        }
        fun ready() = compose.waitUntil(15000) { !vm.state.loading }
        try {
            ready()
            compose.runOnIdle { vm.more() }
            ready()
            compose.runOnIdle {
                assertEquals(1, vm.state.page)
                assertEquals(2, vm.state.next)
                assertEquals(1, vm.state.entries.size)
                assertNotNull(vm.state.error)
                fake.failSecond = false
                vm.more()
            }
            ready()
            compose.runOnIdle {
                assertEquals(2, vm.state.page)
                assertEquals(listOf(1L, 2L), vm.state.entries.map { it.getLong("id") })
                // The first page was cached; appending an online page must not enable writes.
                assertTrue(vm.state.offline)
                fake.offlineFirst = false
                vm.reload()
            }
            ready()
            compose.runOnIdle {
                assertEquals(1, vm.state.page)
                assertEquals(listOf(1L), vm.state.entries.map { it.getLong("id") })
                assertFalse(vm.state.offline)
                vm.filter(ListFilter(search = "missing"))
                assertNull(vm.state.next)
            }
            ready()
            compose.runOnIdle {
                assertTrue(vm.state.entries.isEmpty())
                assertNull(vm.state.next)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { graph.preferences.remove(account.id) }
        }
    }

    private class PagingResources : Resources {
        @Volatile var failSecond = true
        @Volatile var offlineFirst = true
        override suspend fun read(account: String, path: String, cached: Boolean): Resource {
            if (path.contains("search=missing")) return Resource("[]", false)
            if (path.endsWith("&page=2")) {
                if (failSecond) throw IllegalStateException("Page unavailable")
                return Resource("""[{"id":2,"name":"Second"}]""", false)
            }
            return Resource("""[{"id":1,"name":"First"}]""", offlineFirst, 2)
        }
        override suspend fun write(account: String, path: String, method: String, fields: Map<String, String>): String = error("Unexpected write")
        override suspend fun log(account: String, project: Long, job: Long, offset: Long) = LogChunk("", offset, true)
        override suspend fun clear(account: String) { }
    }
}
