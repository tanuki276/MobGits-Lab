package jp.mobgits.lab.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import jp.mobgits.lab.core.auth.AccountRepository
import jp.mobgits.lab.core.model.*
import kotlinx.coroutines.flow.*
import org.json.*

private val Context.mobPreferences by preferencesDataStore("mobgits_preferences_v3")
data class DisplaySettings(val theme: String = "system", val dynamic: Boolean = true, val biometric: Boolean = false,
    val todos: Boolean = false, val mr: Boolean = true, val pipeline: Boolean = true)

class AppPreferences(context: Context) : AccountRepository {
    private val store = context.applicationContext.mobPreferences
    private val directoryKey = stringPreferencesKey("directory")
    override val directory: Flow<AccountDirectory> = store.data.map { decode(it[directoryKey]) }.distinctUntilChanged()
    val settings = store.data.map { p -> DisplaySettings(p[stringPreferencesKey("theme")] ?: "system",
        p[booleanPreferencesKey("dynamic")] ?: true, p[booleanPreferencesKey("biometric")] ?: false,
        p[booleanPreferencesKey("todos")] ?: false, p[booleanPreferencesKey("mr")] ?: true, p[booleanPreferencesKey("pipeline")] ?: true) }
    suspend fun setFlag(key: String, value: Boolean) {
        require(key in setOf("dynamic", "biometric", "todos", "mr", "pipeline"))
        store.edit { it[booleanPreferencesKey(key)] = value }
    }
    suspend fun theme(value: String) { require(value in setOf("system", "light", "dark")); store.edit { it[stringPreferencesKey("theme")] = value } }
    override suspend fun snapshot() = directory.first()
    private suspend fun change(block: (AccountDirectory) -> AccountDirectory) {
        store.edit { it[directoryKey] = encode(block(decode(it[directoryKey]))) }
    }
    override suspend fun saveServer(server: GitLabServer) = change { it.withServer(server) }
    override suspend fun saveAccount(account: GitLabAccount) = change { it.withAccount(account) }
    override suspend fun activate(accountId: String) = change { it.activate(accountId) }
    override suspend fun remove(accountId: String) = change { it.withoutAccount(accountId) }

    private fun encode(value: AccountDirectory) = JSONObject().apply {
        put("servers", JSONArray().apply { value.servers.forEach { s -> put(JSONObject().apply {
            put("url", s.baseUrl); put("name", s.displayName); put("client", s.oauthClientId)
        }) } })
        put("accounts", JSONArray().apply { value.accounts.forEach { a -> put(JSONObject().apply {
            put("server", a.serverId); put("user", a.userId); put("username", a.username)
            put("name", a.displayName); put("avatar", a.avatarUrl); put("active", a.isActive)
        }) } })
    }.toString()
    private fun decode(raw: String?): AccountDirectory {
        if (raw == null) return AccountDirectory()
        val j = JSONObject(raw)
        val servers = j.getJSONArray("servers").let { a -> List(a.length()) { a.getJSONObject(it).let { s -> GitLabServer.create(s.getString("url"), s.getString("client"), s.getString("name")) } } }
        val accounts = j.getJSONArray("accounts").let { a -> List(a.length()) { a.getJSONObject(it).let { v -> GitLabAccount.create(v.getString("server"), v.getLong("user"), v.getString("username"), v.getString("name"), v.optString("avatar").takeUnless { it == "null" || it.isBlank() }, v.optBoolean("active")) } } }
        return AccountDirectory(servers, accounts)
    }
}
