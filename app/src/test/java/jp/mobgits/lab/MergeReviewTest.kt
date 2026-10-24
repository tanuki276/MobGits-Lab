package jp.mobgits.lab

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

}
