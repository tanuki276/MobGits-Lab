package jp.mobgits.lab.core

import jp.mobgits.lab.core.auth.OAuthSession
import jp.mobgits.lab.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AuthModelsTest {
    private val personal = GitLabServer.create("https://gitlab.com", "public-client")
    private val work = GitLabServer.create("https://work.example/gitlab", "work-client")

    @Test fun canonicalUrlKeepsSubpathAndDerivesApiEndpoint() {
        val server = GitLabServer.create(" HTTPS://WORK.EXAMPLE:443/gitlab/// ", "client")
        assertEquals("https://work.example/gitlab", server.baseUrl)
        assertEquals("https://work.example/gitlab/api/v4", server.apiBaseUrl)
        assertFalse(server.isGitLabCom)
        assertEquals(work.id, server.id)
        assertTrue(personal.isGitLabCom)
    }

    @Test fun rejectsUnsafeOrAmbiguousServerUrls() {
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com?token=secret",
            "https://example.com/#callback", "https://example.com:99999", "https://example.com/a/../b",
            "https://example.com/a/%2e%2e/b", "https://example.com/a%2fb", "https:///missing", "https://example.com/a//b")
            .forEach { value ->
                try { GitLabServer.create(value, "client"); fail("Accepted unsafe URL") }
                catch (_: IllegalArgumentException) { }
            }
    }

    @Test fun accountIdentityUsesServerAndUserIdRatherThanUsername() {
        val first = GitLabAccount.create(personal.id, 7, "same", "Personal")
        val renamed = GitLabAccount.create(personal.id, 7, "renamed", "Personal")
        val second = GitLabAccount.create(work.id, 7, "same", "Work")
        assertEquals(first.id, renamed.id)
        assertNotEquals(first.id, second.id)
    }

    @Test fun switchPreservesBothServersAndAllAccountsWithExactlyOneActive() {
        val first = GitLabAccount.create(personal.id, 1, "one", "One", isActive = true)
        val second = GitLabAccount.create(work.id, 1, "two", "Two")
        val third = GitLabAccount.create(work.id, 2, "three", "Three")
        val directory = AccountDirectory(listOf(personal, work), listOf(first, second, third))
        val switched = directory.activate(second.id)
        assertEquals(second.id, switched.activeAccount?.id)
        assertEquals(1, switched.accounts.count { it.isActive })
        assertEquals(3, switched.accounts.size)
        assertEquals(2, switched.servers.size)
        assertEquals(first.id, directory.activeAccount?.id)
    }

    @Test fun reauthenticationUpdatesIdentityAndSelectsAccountAtomically() {
        val first = GitLabAccount.create(personal.id, 1, "one", "One", isActive = true)
        val second = GitLabAccount.create(work.id, 1, "two", "Two")
        val result = AccountDirectory(listOf(personal, work), listOf(first, second))
            .withAccount(second.copy(username = "renamed", isActive = true))
        assertEquals(2, result.accounts.size)
        assertEquals("renamed", result.activeAccount?.username)
        assertEquals(1, result.accounts.count { it.isActive })
        assertNull(result.withoutAccount(second.id).activeAccount)
    }

    @Test fun directoryRejectsTwoActiveAccountsAndUnknownServers() {
        val first = GitLabAccount.create(personal.id, 1, "one", "One", isActive = true)
        val second = GitLabAccount.create(work.id, 1, "two", "Two", isActive = true)
        try { AccountDirectory(listOf(personal, work), listOf(first, second)); fail("Multiple active accounts") }
        catch (_: IllegalArgumentException) { }
        try { AccountDirectory(listOf(personal), listOf(second)); fail("Unknown server") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun existingIdentityCannotBeReboundToAnotherServerOrUser() {
        val account = GitLabAccount.create(personal.id, 1, "one", "One")
        val directory = AccountDirectory(listOf(personal, work), listOf(account))
        try { directory.withServer(work.copy(id = personal.id)); fail("Rebound server identity") }
        catch (_: IllegalArgumentException) { }
        try { directory.withAccount(account.copy(serverId = work.id)); fail("Rebound account server") }
        catch (_: IllegalArgumentException) { }
        try { directory.withAccount(account.copy(userId = 2)); fail("Rebound account user") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun refreshUsesExpiryLeewayAndCredentialsAreRedacted() {
        val expiry = Instant.parse("2026-10-24T12:00:00Z")
        val session = OAuthSession("account", "ACCESS_SECRET", "REFRESH_SECRET", expiry)
        assertFalse(session.needsRefresh(expiry.minusSeconds(61)))
        assertTrue(session.needsRefresh(expiry.minusSeconds(60)))
        assertTrue(session.needsRefresh(expiry.plusSeconds(1)))
        assertFalse(session.toString().contains("ACCESS_SECRET"))
        assertFalse(session.toString().contains("REFRESH_SECRET"))
    }
}
