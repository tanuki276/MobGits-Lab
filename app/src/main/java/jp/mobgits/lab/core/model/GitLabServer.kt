package jp.mobgits.lab.core.model

import java.net.URI
import java.util.UUID

/** Public server metadata. Never contains a client secret or an account token. */
data class GitLabServer(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiBaseUrl: String,
    val oauthClientId: String,
    val isGitLabCom: Boolean
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank())
        require(baseUrl == normalizeBaseUrl(baseUrl)) { "Server URL must be canonical" }
        require(apiBaseUrl == "$baseUrl/api/v4") { "API URL must be derived from the server URL" }
        require(isGitLabCom == (baseUrl == "https://gitlab.com"))
    }

    val oauthConfigured: Boolean get() = oauthClientId.isNotBlank()

    companion object {
        fun create(baseUrl: String, oauthClientId: String, displayName: String? = null): GitLabServer {
            val canonical = normalizeBaseUrl(baseUrl)
            return GitLabServer(
                id = UUID.nameUUIDFromBytes(canonical.toByteArray(Charsets.UTF_8)).toString(),
                displayName = displayName?.trim()?.takeIf(String::isNotEmpty) ?: URI(canonical).host,
                baseUrl = canonical,
                apiBaseUrl = "$canonical/api/v4",
                oauthClientId = oauthClientId.trim(),
                isGitLabCom = canonical == "https://gitlab.com"
            )
        }

        fun normalizeBaseUrl(input: String): String {
            val uri = try { URI(input.trim()) } catch (_: Exception) {
                throw IllegalArgumentException("有効なGitLab URLを入力してください")
            }
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "GitLabの接続先は https:// から始まるURLを入力してください"
            }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "接続先に認証情報・クエリ・フラグメントを含めないでください"
            }
            require(uri.port == -1 || uri.port in 1..65535) { "ポート番号が不正です" }
            val path = uri.path.orEmpty().trimEnd('/')
            require(!path.contains('\\') && !path.contains("//") && path.split('/').none { it == "." || it == ".." }) {
                "接続先のパスが不正です"
            }
            require(!uri.rawPath.orEmpty().contains(Regex("%2f|%5c", RegexOption.IGNORE_CASE))) {
                "接続先のパスにエンコードされた区切りを含めないでください"
            }
            return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port,
                path, null, null).toASCIIString()
        }
    }
}
