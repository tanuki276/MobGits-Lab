package jp.mobgits.lab

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("mobgits_auth", Context.MODE_PRIVATE)

    fun savePat(baseUrl: String, token: String) {
        save(
            StoredAuth(
                baseUrl = baseUrl.trim().trimEnd('/'),
                accessToken = token,
                refreshToken = null,
                expiresAtEpochSeconds = null,
                type = AuthType.PAT
            )
        )
    }

    fun saveOAuth(baseUrl: String, tokens: OAuthTokens) {
        save(
            StoredAuth(
                baseUrl = baseUrl.trim().trimEnd('/'),
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtEpochSeconds = tokens.expiresAtEpochSeconds,
                type = AuthType.OAUTH
            )
        )
    }

    private fun save(auth: StoredAuth) {
        prefs.edit()
            .putString(KEY_BASE_URL, auth.baseUrl)
            .putString(KEY_ACCESS_TOKEN, encrypt(auth.accessToken))
            .putString(KEY_REFRESH_TOKEN, auth.refreshToken?.let(::encrypt))
            .putLong(KEY_EXPIRES_AT, auth.expiresAtEpochSeconds ?: -1L)
            .putString(KEY_AUTH_TYPE, auth.type.name)
            .apply()
    }

    fun load(): StoredAuth? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val access = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val type = runCatching {
            AuthType.valueOf(prefs.getString(KEY_AUTH_TYPE, AuthType.PAT.name)!!)
        }.getOrDefault(AuthType.PAT)
        val expires = prefs.getLong(KEY_EXPIRES_AT, -1L).takeIf { it > 0 }
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() }

        return runCatching {
            StoredAuth(
                baseUrl = baseUrl,
                accessToken = decrypt(access),
                refreshToken = refresh,
                expiresAtEpochSeconds = expires,
                type = type
            )
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "保存された認証情報が壊れています" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "mobgits_gitlab_auth_v2"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_AUTH_TYPE = "auth_type"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

enum class AuthType { OAUTH, PAT }

data class StoredAuth(
    val baseUrl: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long?,
    val type: AuthType
) {
    fun needsRefresh(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        type == AuthType.OAUTH && expiresAtEpochSeconds?.let { nowEpochSeconds >= it - 60 } == true
}
