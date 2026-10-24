package jp.mobgits.lab

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class OAuthManager {
    data class PendingAuth(
        val baseUrl: String,
        val verifier: String,
        val state: String
    )

    fun createAuthorizationUri(baseUrl: String, clientId: String, redirectUri: String): Pair<Uri, PendingAuth> {
        require(clientId.isNotBlank()) { "OAuth Client ID が未設定です" }
        val normalized = normalizeBaseUrl(baseUrl)
        val verifier = randomUrlSafe(64)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(32)

        val uri = Uri.parse("$normalized/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "api")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        return uri to PendingAuth(normalized, verifier, state)
    }

    suspend fun exchangeCode(
        pending: PendingAuth,
        clientId: String,
        redirectUri: String,
        code: String
    ): OAuthTokens = withContext(Dispatchers.IO) {
        postToken(
            pending.baseUrl,
            mapOf(
                "client_id" to clientId,
                "code" to code,
                "grant_type" to "authorization_code",
                "redirect_uri" to redirectUri,
                "code_verifier" to pending.verifier
            )
        )
    }

    suspend fun refresh(
        baseUrl: String,
        clientId: String,
        refreshToken: String
    ): OAuthTokens = withContext(Dispatchers.IO) {
        postToken(
            baseUrl,
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken
            )
        )
    }

    private fun postToken(baseUrl: String, form: Map<String, String>): OAuthTokens {
        val normalized = normalizeBaseUrl(baseUrl)
        val body = form.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val connection = (URL("$normalized/oauth/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MobGits-Lab/0.2")
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GitLabApiException(code, "OAuth認証に失敗しました")
            val json = JSONObject(response)
            OAuthTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresInSeconds = json.optLong("expires_in", 7200L),
                createdAtEpochSeconds = json.optLong("created_at", System.currentTimeMillis() / 1000)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        require(trimmed.startsWith("https://")) { "接続先URLは https:// から始めてください" }
        return trimmed
    }
}

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val createdAtEpochSeconds: Long
) {
    val expiresAtEpochSeconds: Long get() = createdAtEpochSeconds + expiresInSeconds
}
