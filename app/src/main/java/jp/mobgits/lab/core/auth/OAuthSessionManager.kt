package jp.mobgits.lab.core.auth

import android.content.Context
import android.net.Uri
import jp.mobgits.lab.BuildConfig
import jp.mobgits.lab.core.model.*
import jp.mobgits.lab.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.time.Instant

class OAuthSessionManager internal constructor(context: Context, private val accounts: AccountRepository,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()) : SessionManager {
    private val secure = EncryptedSessionStore(context)
    private val mutex = Mutex()
    private val state = MutableStateFlow<SessionStatus>(SessionStatus.SignedOut)
    override val status = state.asStateFlow()
    private val redirect = BuildConfig.OAUTH_REDIRECT_URI

    override suspend fun beginLogin(serverId: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        val server = accounts.snapshot().servers.single { it.id == serverId }
        require(server.oauthConfigured) { "このGitLabは接続準備中です。管理者に接続設定を依頼してください。" }
        val verifier = Pkce.random(64); val nonce = Pkce.random(32)
        secure.put("pending", JSONObject().apply {
            put("server", server.id); put("url", server.baseUrl); put("client", server.oauthClientId)
            put("verifier", verifier); put("state", nonce); put("created", Instant.now().epochSecond)
        }.toString())
        Uri.parse("${server.baseUrl}/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", server.oauthClientId).appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("response_type", "code").appendQueryParameter("scope", "api")
            .appendQueryParameter("state", nonce).appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256").build().toString()
    } }

    override suspend fun completeLogin(callbackUri: String): GitLabAccount = withContext(Dispatchers.IO) { mutex.withLock {
        val fields = Pkce.callback(callbackUri, redirect)
        val pending = JSONObject(secure.get("pending") ?: error("認証をもう一度開始してください"))
        require(Pkce.matches(pending.getString("state"), fields["state"].orEmpty())) { "認証stateが一致しません" }
        val age = Instant.now().epochSecond - pending.getLong("created")
        if (age !in 0..600) { secure.put("pending", null); error("認証がタイムアウトしました。やり直してください") }
        if (fields.containsKey("error")) { secure.put("pending", null); error("GitLabでの認証がキャンセルされました") }
        val server = accounts.snapshot().servers.single { it.id == pending.getString("server") }
        check(server.baseUrl == pending.getString("url") && server.oauthClientId == pending.getString("client")) { "接続先設定が変わりました。認証をやり直してください" }
        val tokens = pending.optJSONObject("tokens") ?: token(server, mapOf("grant_type" to "authorization_code",
            "code" to (fields["code"]?.takeIf { it.isNotBlank() } ?: error("認証codeがありません")),
            "redirect_uri" to redirect, "code_verifier" to pending.getString("verifier"))).also {
            pending.put("tokens", it); secure.put("pending", pending.toString())
        }
        val user = client.newCall(Request.Builder().url("${server.apiBaseUrl}/user").header("Authorization", "Bearer ${tokens.getString("access_token")}").build()).execute().use {
            if (!it.isSuccessful) throw ApiFailure.from(it.code)
            JSONObject(it.body!!.string())
        }
        val account = GitLabAccount.create(server.id, user.getLong("id"), user.getString("username"), user.optString("name"), user.optString("avatar_url").takeUnless { it == "null" }, true)
        secure.write(session(account.id, tokens, server.oauthClientId)); accounts.saveAccount(account)
        secure.put("pending", null); state.value = SessionStatus.Authenticated(account)
        account
    } }

    private fun token(server: GitLabServer, fields: Map<String, String>): JSONObject {
        val body = FormBody.Builder().add("client_id", server.oauthClientId).apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return client.newCall(Request.Builder().url("${server.baseUrl}/oauth/token").post(body).build()).execute().use {
            if (!it.isSuccessful) {
                val oauthError = runCatching { JSONObject(it.body?.string().orEmpty()).optString("error") }.getOrDefault("")
                when (oauthError) {
                    "invalid_client" -> error("GitLabのOAuthアプリ設定を確認してください。Application IDとConfidential（オフ）が必要です。")
                    "invalid_grant" -> {
                        if (fields["grant_type"] == "refresh_token") throw ApiFailure(FailureKind.AUTHENTICATION, it.code)
                        error("認証コードが期限切れ、使用済み、または認証設定が一致しません。アプリからログインをやり直してください。")
                    }
                    "invalid_scope" -> error("GitLabのOAuthアプリでapiスコープを許可してください。")
                    "invalid_request" -> error("GitLabが認証要求を受け付けませんでした。OAuthアプリの登録設定を確認してください。")
                    else -> throw ApiFailure.from(it.code)
                }
            }
            JSONObject(it.body!!.string())
        }
    }
    private fun session(accountId: String, json: JSONObject, clientId: String, fallbackRefresh: String? = null) = OAuthSession(accountId,
        json.getString("access_token"), json.optString("refresh_token").takeUnless { it.isBlank() || it == "null" } ?: fallbackRefresh,
        Instant.ofEpochSecond(json.optLong("created_at", Instant.now().epochSecond) + json.getLong("expires_in")), clientId)

    internal suspend fun credential(accountId: String, rejectedToken: String? = null): String = withContext(Dispatchers.IO) { mutex.withLock {
        val directory = accounts.snapshot()
        val account = directory.accounts.singleOrNull { it.id == accountId } ?: throw ApiFailure(FailureKind.AUTHENTICATION)
        val server = directory.servers.single { it.id == account.serverId }
        val old = try { secure.read(accountId) } catch (_: Exception) { null }
        if (old == null) { if (account.isActive) state.value = SessionStatus.ReauthenticationRequired(accountId); throw ApiFailure(FailureKind.AUTHENTICATION) }
        if (!old.belongsTo(server.oauthClientId)) {
            secure.remove(accountId)
            if (account.isActive) state.value = SessionStatus.ReauthenticationRequired(accountId, true)
            throw ApiFailure(FailureKind.AUTHENTICATION)
        }
        if (!old.needsRefresh(Instant.now()) && (rejectedToken == null || rejectedToken != old.accessToken)) return@withLock old.accessToken
        val refresh = old.refreshToken ?: run { if (account.isActive) state.value = SessionStatus.ReauthenticationRequired(accountId); throw ApiFailure(FailureKind.AUTHENTICATION) }
        try {
            val next = session(accountId, token(server, mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "redirect_uri" to redirect)), server.oauthClientId, refresh)
            secure.write(next); if (account.isActive) state.value = SessionStatus.Authenticated(account); next.accessToken
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (account.isActive) state.value = if (e is ApiFailure && e.kind == FailureKind.AUTHENTICATION) SessionStatus.ReauthenticationRequired(accountId) else SessionStatus.TemporarilyUnavailable(accountId)
            throw e
        }
    } }
    override suspend fun restore() {
        val account = accounts.snapshot().activeAccount ?: run { state.value = SessionStatus.SignedOut; return }
        try { credential(account.id); state.value = SessionStatus.Authenticated(account) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (state.value !is SessionStatus.ReauthenticationRequired) state.value = SessionStatus.TemporarilyUnavailable(account.id) }
    }
    override suspend fun switchAccount(accountId: String) { accounts.activate(accountId); restore() }
    override suspend fun signOut(accountId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        secure.remove(accountId); accounts.remove(accountId)
        state.value = accounts.snapshot().activeAccount?.let { SessionStatus.Authenticated(it) } ?: SessionStatus.SignedOut
    } }
}
