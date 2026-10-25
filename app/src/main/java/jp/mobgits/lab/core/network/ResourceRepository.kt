package jp.mobgits.lab.core.network

import jp.mobgits.lab.core.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Resource(val json: String, val offline: Boolean, val nextPage: Int? = null)
interface Resources {
    suspend fun read(account: String, path: String, cached: Boolean = true): Resource
    suspend fun write(account: String, path: String, method: String, fields: Map<String, String> = emptyMap()): String
    suspend fun writeJson(account: String, path: String, json: String): String = error("JSON writes unsupported")
    suspend fun log(account: String, project: Long, job: Long, offset: Long): LogChunk
    suspend fun clear(account: String)
}
class ResourceRepository(private val remote: NetworkSource, private val cache: ResourceDao) : Resources {
    override suspend fun read(account: String, path: String, cached: Boolean): Resource = withContext(Dispatchers.IO) {
        try {
            val result = remote.request(account, path)
            if (cached) {
                cache.put(CachedResource(account, path, result.body, System.currentTimeMillis()))
                cache.prune(System.currentTimeMillis() - 30L * 86400000); cache.bound()
            }
            Resource(result.body, false, result.nextPage)
        } catch (e: ApiFailure) {
            if (cached && e.kind in setOf(FailureKind.NETWORK, FailureKind.SERVER, FailureKind.RATE_LIMIT)) {
                cache.get(account, path)?.takeIf { System.currentTimeMillis() - it.savedAt <= 30L * 86400000 }?.let { return@withContext Resource(it.json, true) }
            }
            throw e
        }
    }
    override suspend fun write(account: String, path: String, method: String, fields: Map<String, String>): String = withContext(Dispatchers.IO) {
        val result = remote.request(account, path, method, fields).body
        val entity = Regex("^(/api/v4/(?:projects/[^/]+/(?:issues|merge_requests|pipelines|jobs)|todos)/[0-9]+)(?:/|$)").find(path)?.groupValues?.get(1).orEmpty()
        cache.invalidate(account, entity, if (entity.isNotBlank()) entity.substringBeforeLast('/') else path.substringBefore('?'))
        result
    }
    override suspend fun writeJson(account: String, path: String, json: String): String = withContext(Dispatchers.IO) {
        val result = remote.request(account, path, "POST_JSON", mapOf("json" to json)).body
        cache.clear(account)
        result
    }
    override suspend fun log(account: String, project: Long, job: Long, offset: Long) = withContext(Dispatchers.IO) { remote.logChunk(account, project, job, offset) }
    override suspend fun clear(account: String) = cache.clear(account)
}
fun query(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()
