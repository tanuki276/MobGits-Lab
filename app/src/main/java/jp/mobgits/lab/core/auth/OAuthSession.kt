package jp.mobgits.lab.core.auth

import java.time.Duration
import java.time.Instant

/** Credential type is confined to core/auth. Never return it from a UI-facing repository. */
internal class OAuthSession(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant,
    val oauthClientId: String? = null
) {
    init {
        require(accountId.isNotBlank() && accessToken.isNotBlank())
        require(refreshToken == null || refreshToken.isNotBlank())
    }

    fun belongsTo(clientId: String): Boolean = !oauthClientId.isNullOrBlank() && oauthClientId == clientId

    fun needsRefresh(now: Instant, leeway: Duration = Duration.ofSeconds(60)): Boolean {
        require(!leeway.isNegative)
        return !now.isBefore(expiresAt.minus(leeway))
    }

    // Deliberately not a data class: generated toString/copy/componentN would expose credentials.
    override fun toString(): String = "OAuthSession(accountId=$accountId, credentials=[REDACTED])"
}

/** Implement with Keystore-backed encryption; implementations must never log values. */
internal interface SecureTokenStore {
    suspend fun read(accountId: String): OAuthSession?
    suspend fun write(session: OAuthSession)
    suspend fun remove(accountId: String)
}
