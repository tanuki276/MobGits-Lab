package jp.mobgits.lab

import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.feature.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRecoveryTest {
    @Test fun encryptedDraftRestoresAndIsIsolatedByAccountAndProject() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WorkspaceStore(context, "workspace-test", 44)
        val draft = Workspace("base", "main", changes = listOf(FileChange("secret.kt", "create", "private-source-content")))
        try {
            store.write(draft.json())
            assertEquals(draft, Workspace.parse(WorkspaceStore(context, "workspace-test", 44).read()!!))
            assertNull(WorkspaceStore(context, "another-account", 44).read())
            assertNull(WorkspaceStore(context, "workspace-test", 45).read())
            val stored = context.getSharedPreferences("oauth_sessions_v3", 0).all.values.joinToString()
            assertFalse(stored.contains("private-source-content"))
        } finally { store.write(null) }
    }
}
