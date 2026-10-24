package jp.mobgits.lab

import androidx.test.platform.app.InstrumentationRegistry
import jp.mobgits.lab.core.auth.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SecureStorageTest {
    @Test fun sessionAndPendingAuthSurviveStoreRecreationWithoutPlaintext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = EncryptedSessionStore(context)
        first.write(OAuthSession("test-account", "private-access", "private-refresh", Instant.now().plusSeconds(7200)))
        first.put("test-pending", "private-verifier")
        val recreated = EncryptedSessionStore(context)
        assertEquals("private-access", recreated.read("test-account")?.accessToken)
        assertEquals("private-verifier", recreated.get("test-pending"))
        val stored = context.getSharedPreferences("oauth_sessions_v3", 0).all.values.joinToString()
        assertFalse(stored.contains("private-access")); assertFalse(stored.contains("private-refresh")); assertFalse(stored.contains("private-verifier"))
        recreated.remove("test-account"); recreated.put("test-pending", null)
        assertNull(first.read("test-account"))
    }
}
