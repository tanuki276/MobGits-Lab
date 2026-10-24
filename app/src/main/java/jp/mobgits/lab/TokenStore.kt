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

    fun save(baseUrl: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): StoredAuth? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val encrypted = prefs.getString(KEY_TOKEN, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            val token = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
            StoredAuth(baseUrl, token)
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
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
        private const val KEY_ALIAS = "mobgits_gitlab_token"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_IV = "iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

data class StoredAuth(val baseUrl: String, val token: String)
