package jp.mobgits.lab

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MergeReviewTest {
    private val refs = DiffRefs("base", "start", "head")

    @Test fun mixedPatchTracksOldAndNewLines() {
        val rows = UnifiedDiff.parse("@@ -10,3 +20,3 @@\n same\n-old\n+new\n tail\n\\ No newline at end of file\n")
        assertEquals(listOf(null, 10, 11, null, 12, null), rows.map { it.oldLine })
        assertEquals(listOf(null, 20, null, 21, 22, null), rows.map { it.newLine })
        assertFalse(rows.first().commentable)
        assertFalse(rows.last().commentable)
    }

    @Test fun newDeletedAndMultipleHunksHaveCorrectNumbers() {
        val added = UnifiedDiff.parse("@@ -0,0 +1,2 @@\n+one\n+two")
        assertNull(added[1].oldLine)
        assertEquals(1, added[1].newLine)
        val deleted = UnifiedDiff.parse("@@ -1,2 +0,0 @@\n-one\n-two")
        assertEquals(2, deleted.last().oldLine)
        assertNull(deleted.last().newLine)
        val hunks = UnifiedDiff.parse("@@ -1 +1 @@\n-a\n+b\n@@ -90 +80 @@ function\n-c\n+d")
        assertEquals(90, hunks[4].oldLine)
        assertEquals(80, hunks[5].newLine)
    }

    @Test fun headersAndMetadataCannotBecomeCommentPositions() {
        val rows = UnifiedDiff.parse("--- a/test\n+++ b/test\n@@ -1 +1 @@\n-a\n+b\n+outside hunk")
        assertFalse(rows[0].commentable)
        assertFalse(rows[1].commentable)
        assertFalse(rows.last().commentable)
    }

    @Test fun positionsSendOnlyTheApplicableSidesAndKeepRenamePaths() {
        val added = ReviewPosition(refs, "old.kt", "new.kt", null, 12).fields()
        assertEquals("12", added["position[new_line]"])
        assertFalse(added.containsKey("position[old_line]"))
        assertEquals("old.kt", added["position[old_path]"])
        assertEquals("new.kt", added["position[new_path]"])
        val removed = ReviewPosition(refs, "old.kt", "new.kt", 9, null).fields()
        assertFalse(removed.containsKey("position[new_line]"))
        val context = ReviewPosition(refs, "a", "a", 9, 12).fields()
        assertEquals("9", context["position[old_line]"])
        assertEquals("12", context["position[new_line]"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteRefsCannotCreateCommentPosition() {
        ReviewPosition(DiffRefs("", "start", "head"), "a", "a", 1, 1)
    }

    @Test fun draftClosedAndBlockedMergeRequestsCannotMerge() {
        val detail = detail()
        assertTrue(detail.mergeable)
        assertFalse(detail.copy(draft = true).mergeable)
        assertFalse(detail.copy(state = "merged").mergeable)
        assertFalse(detail.copy(canMerge = false).mergeable)
        assertFalse(detail.copy(mergeStatus = "ci_still_running").mergeable)
    }

    @Test fun approveAndMergeSendReviewedShaAndCorrectMethod() = runBlocking {
        for (action in ReviewAction.entries) {
            val fake = FakeApi(mutableListOf(detailJson(), "{}"))
            MergeReviewApi(fake).act(auth, 42, detail(), action)
            val call = fake.calls.last()
            assertEquals(if (action == ReviewAction.MERGE) "PUT" else "POST", call.method)
            assertEquals(mapOf("sha" to "head"), call.fields)
            assertTrue(call.path.endsWith(if (action == ReviewAction.MERGE) "/merge" else "/approve"))
        }
    }

    @Test fun changedHeadBlocksApproveAndMergeBeforeWrite() = runBlocking {
        for (action in ReviewAction.entries) {
            val fake = FakeApi(mutableListOf(detailJson("new-head")))
            try {
                MergeReviewApi(fake).act(auth, 42, detail(), action)
                fail("Stale SHA must be rejected")
            } catch (_: IllegalStateException) { }
            assertEquals(1, fake.calls.size)
            assertEquals("GET", fake.calls.single().method)
        }
    }

    @Test fun commentSendsBodyAndCorrectDiffPosition() = runBlocking {
        val fake = FakeApi(mutableListOf(detailJson(), """{"id":"thread","notes":[]}"""))
        MergeReviewApi(fake).comment(auth, 42, 7, ReviewPosition(refs, "a", "b", null, 3), "日本語 & + review")
        assertEquals("POST", fake.calls.last().method)
        assertEquals("日本語 & + review", fake.calls.last().fields["body"])
        assertEquals("head", fake.calls.last().fields["position[head_sha]"])
        assertFalse(fake.calls.last().fields.containsKey("position[old_line]"))
    }

    @Test fun loadRejectsChangesDuringDiffPagination() = runBlocking {
        val fake = FakeApi(mutableListOf(detailJson(), "[]", "[]", "{}", detailJson("new-head")))
        try {
            MergeReviewApi(fake).load(auth, 42, 7)
            fail("Mixed revisions must never be displayed as reviewable")
        } catch (_: IllegalStateException) { }
    }

    @Test fun fileDiffsFetchAllPagesAndMissingApprovalIsNonFatal() = runBlocking {
        val file = """{"old_path":"a","new_path":"a","diff":"@@ -1 +1 @@\n-a\n+b"}"""
        val firstPage = "[" + List(100) { file }.joinToString(",") + "]"
        val fake = FakeApi(mutableListOf(detailJson(), firstPage, "[$file]", "[]", "invalid JSON", detailJson()))
        val snapshot = MergeReviewApi(fake).load(auth, 42, 7)
        assertEquals(101, snapshot.diffs.size)
        assertTrue(fake.calls.any { it.path.endsWith("diffs?per_page=100&page=2") })
        assertNull(snapshot.approval)
        assertNotNull(snapshot.approvalWarning)
    }

    @Test fun commentsParsePositionsAndIgnoreSystemNotes() = runBlocking {
        val thread = """[{"id":"thread","notes":[{"id":1,"system":true},{"id":2,"author":{"username":"reviewer"},"body":"Please fix","position":{"base_sha":"base","start_sha":"start","head_sha":"head","old_path":"a","new_path":"b","old_line":null,"new_line":3}}]}]"""
        val fake = FakeApi(mutableListOf(detailJson(), "[]", thread, "{}", detailJson()))
        val notes = MergeReviewApi(fake).load(auth, 42, 7).discussions.single().notes
        assertEquals(1, notes.size)
        assertEquals(3, notes.single().position?.newLine)
        assertNull(notes.single().position?.oldLine)
    }

    private fun detail() = ReviewDetail(7, "Review", "", "feature", "main", "opened", false, refs, "mergeable", true)
    private fun detailJson(head: String = "head") = JSONObject().apply {
        put("iid", 7); put("state", "opened"); put("detailed_merge_status", "mergeable")
        put("user", JSONObject().put("can_merge", true))
        put("diff_refs", JSONObject().put("base_sha", "base").put("start_sha", "start").put("head_sha", head))
    }.toString()
    private val auth = StoredAuth("https://gitlab.example.com", "test-token", null, null, AuthType.PAT)
    private data class Call(val path: String, val method: String, val fields: Map<String, String>)
    private class FakeApi(private val responses: MutableList<String>) : GitLabApi() {
        val calls = mutableListOf<Call>()
        override fun request(auth: StoredAuth, path: String, method: String, fields: Map<String, String>): String {
            calls += Call(path, method, fields)
            check(responses.isNotEmpty()) { "Unexpected request: $method $path" }
            return responses.removeAt(0)
        }
    }
}
