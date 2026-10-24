package jp.mobgits.lab

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import jp.mobgits.lab.feature.collaboration.ConfirmAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposeFlowTest {
    @get:Rule val compose = createComposeRule()
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
