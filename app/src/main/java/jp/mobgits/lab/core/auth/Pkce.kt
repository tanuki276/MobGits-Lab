package jp.mobgits.lab.core.auth

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object Pkce {
    fun random(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun callback(value: String, redirect: String): Map<String, String> {
        val uri = URI(value); val expected = URI(redirect)
        require(uri.scheme == expected.scheme && uri.rawAuthority == expected.rawAuthority && uri.rawPath == expected.rawPath && uri.rawFragment == null) { "認証コールバックが不正です" }
        val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
            val p = it.split('=', limit = 2)
            URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.distinctBy { it.first }.size == pairs.size) { "重複した認証パラメーターです" }
        return pairs.toMap()
    }
    fun matches(expected: String, actual: String) = MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
}
