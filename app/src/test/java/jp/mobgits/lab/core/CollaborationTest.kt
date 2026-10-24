package jp.mobgits.lab.core

import jp.mobgits.lab.*
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.*
import jp.mobgits.lab.feature.inbox.DeepLinks
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class CollaborationTest {
    private val d = Destination("mr", 42, 7)
    private val refs = DiffRefs("base", "start", "head")
    private fun detail(head: String = "head") = JSONObject().put("state", "opened").put("detailed_merge_status", "mergeable").put("diff_refs",
        JSONObject().put("base_sha", "base").put("start_sha", "start").put("head_sha", head)).toString()
    @Test fun approvalAndMergeCarryReviewedShaAndCorrectMethod() = runBlocking {
        for (action in listOf("approve", "merge", "unapprove")) {
            val fake = Fake(mutableListOf(Resource(detail(), false)))
            CollaborationUseCase(fake).review("account", d, refs, action)
            val call = fake.writes.single()
            assertEquals("/api/v4/projects/42/merge_requests/7/$action", call.path)
            assertEquals(if (action == "merge") "PUT" else "POST", call.method)
            assertEquals("head", call.fields["sha"])
        }
    }
    @Test fun staleOrOfflineDiffCannotApproveOrMerge() = runBlocking {
        for (value in listOf(Resource(detail("other"), false), Resource(detail(), true))) {
            val fake = Fake(mutableListOf(value))
            try { CollaborationUseCase(fake).review("account", d, refs, "merge"); fail("Must reject stale review") }
            catch (_: IllegalStateException) { }
            assertTrue(fake.writes.isEmpty())
        }
    }
    @Test fun inlinePositionContainsOnlyTheCorrectSideAndPostsDiscussion() = runBlocking {
        val fake = Fake(mutableListOf(Resource(detail(), false)))
        CollaborationUseCase(fake).comment("account", d, "日本語 & +", position = ReviewPosition(refs, "old", "new", null, 8))
        assertEquals("/api/v4/projects/42/merge_requests/7/discussions", fake.writes.single().path)
        assertEquals("8", fake.writes.single().fields["position[new_line]"])
        assertFalse(fake.writes.single().fields.containsKey("position[old_line]"))
    }
    @Test fun issueCreateEditReplyResolveAndTodoUseExpectedEndpoints() = runBlocking {
        val fake = Fake(mutableListOf()); val use = CollaborationUseCase(fake)
        val issue = Destination("issue", 42, 5)
        use.save("a", issue, mapOf("title" to "New"), true)
        use.save("a", issue, mapOf("state_event" to "close"))
        use.comment("a", issue, "reply", "thread / 1")
        use.resolve("a", d, "thread", true)
        use.done("a", 9)
        assertEquals("/api/v4/projects/42/issues", fake.writes[0].path)
        assertEquals("POST", fake.writes[0].method)
        assertEquals("close", fake.writes[1].fields["state_event"])
        assertTrue(fake.writes[2].path.endsWith("/discussions/thread%20%2F%201/notes"))
        assertEquals("true", fake.writes[3].fields["resolved"])
        assertEquals("/api/v4/todos/9/mark_as_done", fake.writes[4].path)
    }
    @Test fun diffPaginationRequestsOneFileAndDoesNotFetchFollowingPages() = runBlocking {
        val fake = Fake(mutableListOf(Resource("[{\"new_path\":\"a\"}]", false, 2)))
        val page = CollaborationUseCase(fake).list("a", d.copy(kind = "diffs"), 1)
        assertEquals(2, page.next)
        assertEquals(1, fake.reads.size)
        assertTrue(fake.reads.single().contains("per_page=1&page=1"))
    }
    @Test fun accountScopedDeepLinksRoundTripAndRejectMalformedInputs() {
        val encoded = DeepLinks.create("account-one", Destination("issue", 42, 5))
        assertEquals("account-one", DeepLinks.parse(encoded)?.first)
        assertEquals(5L, DeepLinks.parse(encoded)?.second?.id)
        listOf("https://open?account=a&kind=issue&project=1&id=2", "mobgits://open?account=a&kind=issue&project=-1&id=2",
            "mobgits://open?account=a&kind=issue&project=1&id=2&id=3", "mobgits://evil/open?account=a&kind=issue&project=1&id=2")
            .forEach { assertNull(DeepLinks.parse(it)) }
    }
    @Test fun inboxUsesProjectLocalIidRatherThanGlobalTargetId() {
        val json = JSONObject("""{"target_type":"MergeRequest","action_name":"review_requested","project":{"id":42},"target":{"id":9000,"iid":7}}""")
        assertEquals(7L, todoDestination(json)?.id)
        assertEquals("レビュー依頼", todoCategory(json))
    }
    private data class Write(val path: String, val method: String, val fields: Map<String, String>)
    private class Fake(val responses: MutableList<Resource>) : Resources {
        val reads = mutableListOf<String>(); val writes = mutableListOf<Write>()
        override suspend fun read(account: String, path: String, cached: Boolean): Resource { reads += path; return responses.removeAt(0) }
        override suspend fun write(account: String, path: String, method: String, fields: Map<String, String>): String { writes += Write(path, method, fields); return "{}" }
        override suspend fun log(account: String, project: Long, job: Long, offset: Long) = LogChunk("", offset, true)
        override suspend fun clear(account: String) { }
    }
}
