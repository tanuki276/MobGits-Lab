package jp.mobgits.lab.core

import jp.mobgits.lab.core.auth.OAuthSession
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OAuthClientBindingTest {
    @Test fun tokensRequireTheirIssuingClientIncludingUnexpiredTokens() {
        val session = OAuthSession("account", "access", "refresh", Instant.now().plusSeconds(7200), "old-client")
        assertTrue(session.belongsTo("old-client"))
        assertFalse(session.belongsTo("new-client"))
        assertFalse(session.needsRefresh(Instant.now()))
    }
    @Test fun legacySessionsRequireReauthentication() {
        assertFalse(OAuthSession("account", "access", "refresh", Instant.now()).belongsTo("client"))
    }
}
