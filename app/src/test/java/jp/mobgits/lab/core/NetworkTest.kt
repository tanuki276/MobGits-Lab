package jp.mobgits.lab.core

import jp.mobgits.lab.core.auth.Pkce
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.core.database.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class NetworkTest {
    @Test fun pkceMatchesRfc7636AndCallbackRequiresExactRedirectAndUniqueState() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals("valid", Pkce.callback("mobgits://oauth/callback?state=valid&code=code", "mobgits://oauth/callback")["state"])
        for (uri in listOf("mobgits://oauth/evil?state=valid", "mobgits://oauth/callback?state=a&state=b", "mobgits://user@oauth/callback?state=a")) {
            try { Pkce.callback(uri, "mobgits://oauth/callback"); fail("Invalid callback") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(Pkce.matches("a", "b"))
    }
    @Test fun interceptorRefreshesOnceAndNeverFollowsRedirects() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody("ok"))
            val requested = mutableListOf<String?>()
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor(AuthInterceptor { _, rejected ->
                requested += rejected; if (rejected == null) "old" else "new"
            }).build()
            val base = server.url("/api/v4").toString()
            client.newCall(Request.Builder().url("$base/user").tag(AccountKey::class.java, AccountKey("a", base)).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf(null, "old"), requested)
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun cacheIsAccountScopedAndDoesNotMaskPermissionFailures() = runBlocking {
        val cache = FakeDao(); val remote = FakeRemote(); val repo = ResourceRepository(remote, cache)
        repo.read("a", "/api/v4/projects/1/issues/1")
        remote.failure = ApiFailure(FailureKind.NETWORK)
        assertTrue(repo.read("a", "/api/v4/projects/1/issues/1").offline)
        try { repo.read("b", "/api/v4/projects/1/issues/1"); fail("Cross-account cache leak") } catch (_: ApiFailure) { }
        remote.failure = ApiFailure(FailureKind.FORBIDDEN)
        try { repo.read("a", "/api/v4/projects/1/issues/1"); fail("Must not hide forbidden") } catch (e: ApiFailure) { assertEquals(FailureKind.FORBIDDEN, e.kind) }
    }
    @Test fun successfulMutationInvalidatesOnlyThatAccountsCache() = runBlocking {
        val dao = FakeDao(); val remote = FakeRemote(); val repo = ResourceRepository(remote, dao)
        repo.read("a", "/api/v4/projects/1/issues/1"); repo.read("a", "/api/v4/projects/1/issues/2"); repo.read("b", "/api/v4/projects/1/issues/1")
        repo.write("a", "/api/v4/projects/1/issues/1", "PUT", mapOf("title" to "edit"))
        assertNull(dao.get("a", "/api/v4/projects/1/issues/1")); assertNotNull(dao.get("a", "/api/v4/projects/1/issues/2")); assertNotNull(dao.get("b", "/api/v4/projects/1/issues/1"))
    }
    @Test fun chunkBoundariesDoNotCorruptJapaneseLogText() {
        val bytes = ByteArray(32767) { 'a'.code.toByte() } + "漢字".toByteArray(Charsets.UTF_8)
        val first = decodeLogChunk(bytes.copyOfRange(0, 32768), 0)
        assertEquals(32767L, first.nextOffset)
        val second = decodeLogChunk(bytes.copyOfRange(first.nextOffset.toInt(), bytes.size), first.nextOffset)
        assertEquals(bytes.toString(Charsets.UTF_8), first.text + second.text)
        assertTrue(second.end)
    }
    @Test fun commonErrorsDistinguishPermissionsConflictAndRateLimit() {
        assertEquals(FailureKind.AUTHENTICATION, ApiFailure.from(401).kind)
        assertEquals(FailureKind.FORBIDDEN, ApiFailure.from(403).kind)
        assertEquals(FailureKind.NOT_FOUND, ApiFailure.from(404).kind)
        assertEquals(FailureKind.CONFLICT, ApiFailure.from(409).kind)
        assertEquals(120L, ApiFailure.from(429, "120").retryAfterSeconds)
        assertEquals(FailureKind.SERVER, ApiFailure.from(503).kind)
    }
    private class FakeRemote : NetworkSource {
        var failure: ApiFailure? = null
        override fun request(id: String, path: String, method: String, fields: Map<String, String>): NetworkPage { failure?.let { throw it }; return NetworkPage("{}", null) }
        override fun logChunk(id: String, project: Long, job: Long, offset: Long) = LogChunk("", offset, true)
    }
    private class FakeDao : ResourceDao {
        val values = mutableMapOf<Pair<String, String>, CachedResource>()
        override suspend fun get(account: String, path: String) = values[account to path]
        override suspend fun put(resource: CachedResource) { values[resource.accountId to resource.path] = resource }
        override suspend fun clear(account: String) { values.keys.removeAll { it.first == account } }
        override suspend fun invalidate(account: String, entity: String, collection: String) {
            values.keys.removeAll { it.first == account && ((entity.isNotBlank() && (it.second == entity || it.second.startsWith("$entity/") || it.second.startsWith("$entity?"))) || it.second.startsWith("$collection?")) }
        }
        override suspend fun prune(cutoff: Long) { }
        override suspend fun bound() { }
    }
}
