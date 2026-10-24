package jp.mobgits.lab.core

import android.content.Context
import jp.mobgits.lab.BuildConfig
import jp.mobgits.lab.core.auth.OAuthSessionManager
import jp.mobgits.lab.core.datastore.AppPreferences
import jp.mobgits.lab.core.database.MobDatabase
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.core.model.GitLabServer

class AppGraph private constructor(context: Context) {
    val preferences = AppPreferences(context)
    val sessions = OAuthSessionManager(context, preferences)
    val database = MobDatabase.create(context)
    val transport = GitLabTransport(preferences, sessions)
    val resources = ResourceRepository(transport, database.resources())
    suspend fun initialize() {
        if (preferences.snapshot().servers.none { it.isGitLabCom }) preferences.saveServer(GitLabServer.create("https://gitlab.com", BuildConfig.GITLAB_CLIENT_ID, "GitLab.com"))
        sessions.restore()
    }
    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context): AppGraph = instance ?: synchronized(this) { instance ?: AppGraph(context.applicationContext).also { instance = it } }
    }
}
