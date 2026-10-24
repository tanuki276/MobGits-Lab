package jp.mobgits.lab.core.network

import jp.mobgits.lab.core.auth.AccountRepository
import jp.mobgits.lab.core.auth.OAuthSessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class AccountKey(val id: String, val apiBase: String)
internal interface GitLabService {
    @Streaming @GET fun get(@Url url: String, @Tag account: AccountKey, @Header("Range") range: String? = null): Call<ResponseBody>
    @POST fun post(@Url url: String, @Tag account: AccountKey, @Body body: RequestBody): Call<ResponseBody>
    @PUT fun put(@Url url: String, @Tag account: AccountKey, @Body body: RequestBody): Call<ResponseBody>
    @DELETE fun delete(@Url url: String, @Tag account: AccountKey): Call<ResponseBody>
}

internal class AuthInterceptor(private val credential: (String, String?) -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val tag = chain.request().tag(AccountKey::class.java) ?: error("Account scope missing")
        check(chain.request().url.toString().startsWith("${tag.apiBase}/")) { "接続先が一致しません" }
        val token = credential(tag.id, null)
        fun request(value: String) = chain.request().newBuilder().header("Authorization", "Bearer $value").build()
        val response = chain.proceed(request(token))
        if (response.code != 401) return response
        response.close()
        val refreshed = credential(tag.id, token)
        return chain.proceed(request(refreshed))
    }
}

data class NetworkPage(val body: String, val nextPage: Int?)
data class LogChunk(val text: String, val nextOffset: Long, val end: Boolean)

interface NetworkSource {
    fun request(id: String, path: String, method: String = "GET", fields: Map<String, String> = emptyMap()): NetworkPage
    fun logChunk(id: String, project: Long, job: Long, offset: Long): LogChunk
}

class GitLabTransport(accounts: AccountRepository, sessions: OAuthSessionManager, private val limits: RateLimits = MemoryRateLimits()) : NetworkSource {
    private val directory = accounts
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor { id, rejected -> runBlocking { sessions.credential(id, rejected) } }).build()
    private val service = Retrofit.Builder().baseUrl("https://gitlab.com/").client(client).build().create(GitLabService::class.java)
    private fun scope(id: String): AccountKey = runBlocking {
        val d = directory.snapshot(); val a = d.accounts.singleOrNull { it.id == id } ?: throw ApiFailure(FailureKind.AUTHENTICATION)
        AccountKey(id, d.servers.single { it.id == a.serverId }.apiBaseUrl)
    }
    private fun url(scope: AccountKey, path: String): String {
        require(path.startsWith("/api/v4/") && path.substringBefore('?').split('/').none { it == ".." || it == "." }) { "API pathが不正です" }
        return scope.apiBase + path.removePrefix("/api/v4")
    }
    override fun request(id: String, path: String, method: String, fields: Map<String, String>): NetworkPage {
        val scope = scope(id); checkRate(id); val url = url(scope, path)
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> if (k.endsWith("[]")) v.split(',').forEach { add(k, it.trim()) } else add(k, v) } }.build()
        return execute(when (method) {
            "GET" -> service.get(url, scope); "POST" -> service.post(url, scope, body)
            "PUT" -> service.put(url, scope, body); "DELETE" -> service.delete(url, scope)
            else -> error("Unsupported method")
        }, id) { response ->
            val input = response.body() ?: return@execute NetworkPage("", null)
            val bytes = input.byteStream().use { it.readNBytesCompat(8 * 1024 * 1024 + 1) }
            check(bytes.size <= 8 * 1024 * 1024) { "応答が大きすぎます。絞り込みを変更してください" }
            NetworkPage(bytes.toString(Charsets.UTF_8), response.headers()["X-Next-Page"]?.toIntOrNull())
        }
    }
    override fun logChunk(id: String, project: Long, job: Long, offset: Long): LogChunk {
        require(offset >= 0)
        val scope = scope(id); checkRate(id)
        return try { execute(service.get(url(scope, "/api/v4/projects/$project/jobs/$job/trace"), scope, "bytes=$offset-${offset + 32767}"), id) { response ->
            val stream = response.body()!!.byteStream()
            stream.use {
                if (response.code() == 200) {
                    var remaining = offset
                    while (remaining > 0) { val skipped = it.skip(remaining); if (skipped <= 0) return@execute LogChunk("", offset, true); remaining -= skipped }
                } else {
                    val start = response.headers()["Content-Range"]?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    check(start == offset) { "ログ位置を検証できませんでした" }
                }
                val bytes = it.readNBytesCompat(32768)
                decodeLogChunk(bytes, offset)
            }
        }
        } catch (error: ApiFailure) { if (error.status == 416) LogChunk("", offset, true) else throw error }
    }
    private fun checkRate(account: String) {
        val remaining = limits.until(account) - System.currentTimeMillis()
        if (remaining > 0) throw ApiFailure(FailureKind.RATE_LIMIT, 429, (remaining + 999) / 1000)
    }
    private fun <T> execute(call: Call<ResponseBody>, account: String, block: (retrofit2.Response<ResponseBody>) -> T): T {
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                val error = ApiFailure.from(response.code(), response.headers()["Retry-After"])
                if (error.kind == FailureKind.RATE_LIMIT) limits.defer(account, System.currentTimeMillis() + (error.retryAfterSeconds ?: 60).coerceAtMost((Long.MAX_VALUE - System.currentTimeMillis()) / 1000) * 1000)
                throw error
            }
            return try { block(response) } finally { response.body()?.close() }
        } catch (e: ApiFailure) { throw e }
        catch (_: IOException) { throw ApiFailure(FailureKind.NETWORK) }
    }
}
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (output.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - output.size())); if (n < 0) break; output.write(buffer, 0, n) }
    return output.toByteArray()
}

internal fun decodeLogChunk(bytes: ByteArray, offset: Long): LogChunk {
    val end = bytes.size < 32768
    var consumed = bytes.size
    if (!end && bytes.isNotEmpty()) {
        var lead = bytes.lastIndex
        while (lead >= 0 && (bytes[lead].toInt() and 0xC0) == 0x80) lead--
        if (lead >= 0) {
            val first = bytes[lead].toInt() and 0xFF
            val length = when { (first and 0xF8) == 0xF0 -> 4; (first and 0xF0) == 0xE0 -> 3; (first and 0xE0) == 0xC0 -> 2; else -> 1 }
            if (lead + length > bytes.size) consumed = lead
        }
    }
    return LogChunk(bytes.copyOfRange(0, consumed).toString(Charsets.UTF_8), offset + consumed, end)
}
