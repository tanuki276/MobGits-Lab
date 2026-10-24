package jp.mobgits.lab.core.network

import android.content.Context

/** Backoff survives worker/process restarts. Keys contain account IDs, never credentials. */
interface RateLimits {
    fun until(account: String): Long
    fun defer(account: String, untilMillis: Long)
}
class StoredRateLimits(context: Context) : RateLimits {
    private val prefs = context.getSharedPreferences("api_backoff_v1", Context.MODE_PRIVATE)
    override fun until(account: String) = prefs.getLong(account, 0)
    override fun defer(account: String, untilMillis: Long) { prefs.edit().putLong(account, untilMillis).commit() }
}
internal class MemoryRateLimits : RateLimits {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override fun until(account: String) = values[account] ?: 0L
    override fun defer(account: String, untilMillis: Long) { values[account] = untilMillis }
}
