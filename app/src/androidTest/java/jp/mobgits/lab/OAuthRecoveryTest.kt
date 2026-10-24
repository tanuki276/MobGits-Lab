package jp.mobgits.lab

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.core.auth.*
import jp.mobgits.lab.core.datastore.AppPreferences
import jp.mobgits.lab.core.model.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OAuthRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun pendingPkceSurvivesManagerRecreationAndExpiredCallbackIsRejected() = runBlocking {
        val accounts = AppPreferences(context)
        val server = GitLabServer.create("https://recovery.example", "test-client")
        accounts.saveServer(server)
        val uri = Uri.parse(OAuthSessionManager(context, accounts).beginLogin(server.id))
        val secure = EncryptedSessionStore(context)
        val saved = JSONObject(secure.get("pending")!!)
        assertEquals(server.id, saved.getString("server"))
        assertEquals(saved.getString("state"), uri.getQueryParameter("state"))
        assertEquals(Pkce.challenge(saved.getString("verifier")), uri.getQueryParameter("code_challenge"))
        saved.put("created", Instant.now().minusSeconds(601).epochSecond)
        secure.put("pending", saved.toString())
        val recreated = OAuthSessionManager(context, accounts)
        try { recreated.completeLogin("mobgits://oauth/callback?state=${saved.getString("state")}&code=test"); fail("Expired callback must be rejected") }
        catch (_: IllegalStateException) { }
        assertNull(secure.get("pending"))
    }
    @Test fun concurrent401sRefreshOnceAndPersistRotatedTokens() = runBlocking {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":7200}"""))
            server.start()
            val accounts = AppPreferences(context)
            val gitlab = GitLabServer.create(server.url("/").toString(), "test-client")
            val account = GitLabAccount.create(gitlab.id, 123, "refresh-test", "Test")
            accounts.saveServer(gitlab); accounts.saveAccount(account)
            val secure = EncryptedSessionStore(context)
            secure.write(OAuthSession(account.id, "old-access", "old-refresh", Instant.now().minusSeconds(60)))
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).followRedirects(false).build()
            val sessions = OAuthSessionManager(context, accounts, client)
            try {
                val values = coroutineScope { List(12) { async(Dispatchers.IO) { sessions.credential(account.id, "old-access") } }.awaitAll() }
                assertTrue(values.all { it == "new-access" })
                assertEquals(1, server.requestCount)
                assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=old-refresh"))
                assertEquals("new-refresh", EncryptedSessionStore(context).read(account.id)?.refreshToken)
            } finally { secure.remove(account.id); accounts.remove(account.id) }
        }
    }
}
