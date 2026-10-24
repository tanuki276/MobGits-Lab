package jp.mobgits.lab.core.model

import java.util.UUID

/** UI-safe identity. The username is display data, never the storage key. */
data class GitLabAccount(
    val id: String,
    val serverId: String,
    val userId: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val isActive: Boolean
) {
    init {
        require(id.isNotBlank() && serverId.isNotBlank())
        require(userId > 0) { "GitLab user ID must be positive" }
        require(username.isNotBlank())
    }

    companion object {
        fun create(serverId: String, userId: Long, username: String, displayName: String,
                   avatarUrl: String? = null, isActive: Boolean = false): GitLabAccount = GitLabAccount(
            UUID.nameUUIDFromBytes("$serverId:$userId".toByteArray(Charsets.UTF_8)).toString(),
            serverId, userId, username, displayName, avatarUrl, isActive
        )
    }
}

/** One immutable snapshot: persistence must replace this atomically, including active selection. */
class AccountDirectory(servers: List<GitLabServer> = emptyList(), accounts: List<GitLabAccount> = emptyList()) {
    val servers: List<GitLabServer> = servers.toList()
    val accounts: List<GitLabAccount> = accounts.toList()
    val activeAccount: GitLabAccount? get() = accounts.singleOrNull { it.isActive }

    init {
        require(this.servers.distinctBy { it.id }.size == this.servers.size) { "Duplicate server ID" }
        require(this.servers.distinctBy { it.baseUrl }.size == this.servers.size) { "Duplicate server URL" }
        require(this.accounts.distinctBy { it.id }.size == this.accounts.size) { "Duplicate account ID" }
        require(this.accounts.distinctBy { it.serverId to it.userId }.size == this.accounts.size) { "Duplicate account identity" }
        require(this.accounts.count { it.isActive } <= 1) { "Only one account may be active" }
        require(this.accounts.all { account -> this.servers.any { it.id == account.serverId } }) { "Unknown server" }
    }

    fun withServer(server: GitLabServer): AccountDirectory =
        AccountDirectory(servers.filterNot { it.id == server.id } + server, accounts)

    fun withAccount(account: GitLabAccount): AccountDirectory {
        val retained = accounts.filterNot { it.id == account.id }
        return AccountDirectory(servers, retained.map { if (account.isActive) it.copy(isActive = false) else it } + account)
    }

    fun activate(accountId: String): AccountDirectory {
        require(accounts.any { it.id == accountId }) { "Unknown account" }
        return AccountDirectory(servers, accounts.map { it.copy(isActive = it.id == accountId) })
    }

    /** Removing the active account goes to account selection instead of silently switching identity. */
    fun withoutAccount(accountId: String): AccountDirectory =
        AccountDirectory(servers, accounts.filterNot { it.id == accountId })
}
