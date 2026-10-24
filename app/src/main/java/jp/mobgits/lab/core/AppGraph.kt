package jp.mobgits.lab.core

import android.content.Context
import jp.mobgits.lab.BuildConfig
import jp.mobgits.lab.core.auth.OAuthSessionManager
import jp.mobgits.lab.core.datastore.AppPreferences
import jp.mobgits.lab.core.database.MobDatabase
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.core.model.GitLabServer

class AppGraph private constructor(private val context: Context) {
    val preferences = AppPreferences(context)
    val sessions = OAuthSessionManager(context, preferences)
    val database = MobDatabase.create(context)
    val transport = GitLabTransport(preferences, sessions, StoredRateLimits(context))
    val resources = ResourceRepository(transport, database.resources())
    suspend fun initialize() {
        // Legacy single-account PAT/OAuth data is intentionally not reused. Re-authenticate with PKCE.
        context.getSharedPreferences("mobgits_auth", Context.MODE_PRIVATE).edit().clear().commit()
        if (preferences.snapshot().servers.none { it.isGitLabCom && it.oauthClientId == BuildConfig.GITLAB_CLIENT_ID }) preferences.saveServer(GitLabServer.create("https://gitlab.com", BuildConfig.GITLAB_CLIENT_ID, "GitLab.com"))
        sessions.restore()
    }
    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context): AppGraph = instance ?: synchronized(this) { instance ?: AppGraph(context.applicationContext).also { instance = it } }
    }
}
