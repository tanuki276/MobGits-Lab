package jp.mobgits.lab

import org.junit.Assert.*
import org.junit.Test

class GitLabJobTest {
    @Test fun terminalJobsCanRetryButCannotCancel() {
        listOf("success", "failed", "canceled").forEach {
            val job = GitLabJob(1, "test", "test", it)
            assertTrue(job.allows(JobAction.RETRY))
            assertFalse(job.allows(JobAction.CANCEL))
        }
    }
    @Test fun runningJobCanOnlyCancel() {
        val job = GitLabJob(1, "test", "test", "running")
        assertFalse(job.allows(JobAction.RETRY))
        assertTrue(job.allows(JobAction.CANCEL))
    }
    @Test fun unknownAndManualJobsHaveNoActions() {
        listOf("manual", "skipped", "unknown").forEach { status ->
            JobAction.entries.forEach { assertFalse(GitLabJob(1, "test", "test", status).allows(it)) }
        }
    }
}
