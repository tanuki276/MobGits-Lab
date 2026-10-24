package jp.mobgits.lab.core.auth

import jp.mobgits.lab.core.model.AccountDirectory
import jp.mobgits.lab.core.model.GitLabAccount
import jp.mobgits.lab.core.model.GitLabServer
import kotlinx.coroutines.flow.Flow

/** Persistence boundary for metadata. Credential storage is a separate, internal dependency. */
interface AccountRepository {
    val directory: Flow<AccountDirectory>
    suspend fun saveServer(server: GitLabServer)
    suspend fun snapshot(): AccountDirectory
    suspend fun saveAccount(account: GitLabAccount)
    suspend fun activate(accountId: String)
    suspend fun remove(accountId: String)
}

/** Public authentication results expose identity/state only, never tokens or PKCE material. */
sealed interface SessionStatus {
    data object SignedOut : SessionStatus
    data class Authenticated(val account: GitLabAccount) : SessionStatus
    data class ReauthenticationRequired(val accountId: String) : SessionStatus
    data class TemporarilyUnavailable(val accountId: String) : SessionStatus
}

interface SessionManager {
    val status: Flow<SessionStatus>
    /** Persist pending auth before returning a browser URI. No client secrets are supported. */
    suspend fun beginLogin(serverId: String): String
    /** Validate exact redirect, state, TTL and single use before consuming the callback. */
    suspend fun completeLogin(callbackUri: String): GitLabAccount
    suspend fun restore()
    suspend fun switchAccount(accountId: String)
    suspend fun signOut(accountId: String)
}
