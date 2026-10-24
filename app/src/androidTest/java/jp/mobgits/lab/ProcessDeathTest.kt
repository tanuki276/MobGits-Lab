package jp.mobgits.lab

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.core.auth.*
import jp.mobgits.lab.core.datastore.AppPreferences
import jp.mobgits.lab.core.model.GitLabServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProcessDeathPrepareTest {
    @Test fun preparePendingBrowserLogin() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("processDeath") == "prepare")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accounts = AppPreferences(context)
        val server = GitLabServer.create("https://process-death.example", "public-client")
        accounts.saveServer(server)
        val uri = OAuthSessionManager(context, accounts).beginLogin(server.id)
        EncryptedSessionStore(context).put("process-test-uri", uri)
        assertNotNull(Uri.parse(uri).getQueryParameter("state"))
    }
}
class ProcessDeathVerifyTest {
    @Test fun pendingStateAndVerifierSurviveForceStop() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("processDeath") == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = EncryptedSessionStore(context)
        val uri = Uri.parse(secure.get("process-test-uri")!!)
        val pending = JSONObject(secure.get("pending")!!)
        assertEquals(uri.getQueryParameter("state"), pending.getString("state"))
        assertEquals(uri.getQueryParameter("code_challenge"), Pkce.challenge(pending.getString("verifier")))
        val accounts = AppPreferences(context)
        assertTrue(accounts.snapshot().servers.any { it.id == pending.getString("server") })
        pending.put("created", java.time.Instant.now().minusSeconds(601).epochSecond)
        secure.put("pending", pending.toString())
        val nonce = pending.getString("state")
        try { OAuthSessionManager(context, accounts).completeLogin("mobgits://oauth/callback?state=$nonce&code=test"); fail("Expired state accepted") }
        catch (_: IllegalStateException) { }
        assertNull(secure.get("pending")); secure.put("process-test-uri", null)
    }
}
