package jp.mobgits.lab

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import jp.mobgits.lab.feature.collaboration.ConfirmAction
import jp.mobgits.lab.feature.collaboration.ListSearchBar
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposeFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun searchSupportsKeyboardClearAndExternalReset() {
        val applied = mutableStateOf("")
        var submitted = ""
        compose.setContent { MaterialTheme {
            ListSearchBar(applied.value) { submitted = it; applied.value = it }
        } }
        compose.onNode(hasSetTextAction()).performTextInput("  日本語  ")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals("日本語", submitted) }
        compose.onNodeWithContentDescription("検索をクリア").performClick()
        compose.runOnIdle { assertEquals("", submitted); applied.value = "restored" }
        compose.onNode(hasSetTextAction()).assertTextContains("restored")
        compose.runOnIdle { applied.value = "" }
        compose.onNodeWithContentDescription("検索をクリア").assertDoesNotExist()
    }
    @Test fun destructiveActionRequiresConfirmationAndCanBeDismissed() {
        var calls = 0
        compose.setContent { MaterialTheme { ConfirmAction("Merge") { calls++ } } }
        compose.onNodeWithText("Merge").performClick()
        assertEquals(0, calls)
        compose.onNodeWithText("戻る").performClick()
        assertEquals(0, calls)
        compose.onNodeWithText("Merge").performClick()
        compose.onAllNodesWithText("Merge").onLast().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun changedRevisionClosesAnOpenConfirmation() {
        val revision = mutableStateOf("old")
        var calls = 0
        compose.setContent { MaterialTheme { ConfirmAction("Merge", identity = revision.value) { calls++ } } }
        compose.onNodeWithText("Merge").performClick()
        compose.onNodeWithText("Merge しますか？").assertExists()
        compose.runOnIdle { revision.value = "new" }
        compose.onNodeWithText("Merge しますか？").assertDoesNotExist()
        assertEquals(0, calls)
    }
    @Test fun offlineActionIsDisabled() {
        compose.setContent { MaterialTheme { ConfirmAction("Approve", enabled = false) { error("Offline mutation") } } }
        compose.onNodeWithText("Approve").assertIsNotEnabled()
    }
}
