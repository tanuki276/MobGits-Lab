package jp.mobgits.lab.core.auth

import android.content.Context
import android.security.keystore.*
import android.util.Base64
import java.security.KeyStore
import java.time.Instant
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class EncryptedSessionStore(context: Context) : SecureTokenStore {
    private val prefs = context.getSharedPreferences("oauth_sessions_v3", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("mobgits_oauth_v3", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mobgits_oauth_v3", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(name: String, value: String?) {
        val encoded = value?.let {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray()) }
            Base64.encodeToString(c.iv + c.doFinal(it.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        check(prefs.edit().putString(name, encoded).commit()) { "認証情報を保存できませんでした" }
    }
    @Synchronized fun get(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size >= 28) { "認証情報を再登録してください" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(name.toByteArray())
        }
        return c.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    override suspend fun read(accountId: String): OAuthSession? = get("session:$accountId")?.let {
        val j = JSONObject(it)
        OAuthSession(accountId, j.getString("access"), j.optString("refresh").takeUnless { v -> v.isBlank() || v == "null" }, Instant.ofEpochSecond(j.getLong("expiry")), j.optString("client").takeIf { v -> v.isNotBlank() && v != "null" })
    }
    override suspend fun write(session: OAuthSession) = put("session:${session.accountId}", JSONObject().apply {
        put("client", session.oauthClientId); put("access", session.accessToken); put("refresh", session.refreshToken); put("expiry", session.expiresAt.epochSecond)
    }.toString())
    override suspend fun remove(accountId: String) = put("session:$accountId", null)
}
