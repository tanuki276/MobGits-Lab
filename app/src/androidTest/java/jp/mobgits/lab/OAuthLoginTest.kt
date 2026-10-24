package jp.mobgits.lab

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.core.auth.*
import jp.mobgits.lab.core.datastore.AppPreferences
import jp.mobgits.lab.core.model.GitLabServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Assert.*
import org.junit.Test

class OAuthLoginTest {
    @Test fun callbackAfterManagerRecreationExchangesPkceAndCreatesAccountOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accounts = AppPreferences(context)
        val secure = EncryptedSessionStore(context)
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        MockWebServer().use { web ->
            web.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
            web.enqueue(MockResponse().setBody("""{"access_token":"login-access","refresh_token":"login-refresh","expires_in":7200}"""))
            web.enqueue(MockResponse().setBody("""{"id":700,"username":"oauth-user","name":"OAuth user","avatar_url":null}"""))
            web.start()
            val server = GitLabServer.create(web.url("/").toString(), "public-client")
            accounts.saveServer(server)
            val client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).followRedirects(false).build()
            val authorization = Uri.parse(OAuthSessionManager(context, accounts, client).beginLogin(server.id))
            val nonce = authorization.getQueryParameter("state")!!
            val recreated = OAuthSessionManager(context, accounts, client)
            try {
                recreated.completeLogin("mobgits://oauth/callback?state=wrong&code=one-time")
                fail("Wrong state accepted")
            } catch (_: IllegalArgumentException) { }
            val account = recreated.completeLogin("mobgits://oauth/callback?state=$nonce&code=one-time")
            try {
                assertEquals(account.id, accounts.snapshot().activeAccount?.id)
                assertEquals("login-access", secure.read(account.id)?.accessToken)
                assertNull(secure.get("pending"))
                val exchange = web.takeRequest()
                assertEquals("/oauth/token", exchange.path)
                val body = exchange.body.readUtf8()
                assertTrue(body.contains("code_verifier=")); assertFalse(body.contains("client_secret"))
                val user = web.takeRequest()
                assertEquals("/api/v4/user", user.path); assertEquals("Bearer login-access", user.getHeader("Authorization"))
                try { recreated.completeLogin("mobgits://oauth/callback?state=$nonce&code=one-time"); fail("Callback replay accepted") }
                catch (_: IllegalStateException) { }
                assertEquals(2, web.requestCount)
            } finally { recreated.signOut(account.id) }
        }
    }
}
