package jp.mobgits.lab.core.database

import android.content.Context
import androidx.room.*

@Entity(tableName = "resource_cache", primaryKeys = ["accountId", "path"])
data class CachedResource(val accountId: String, val path: String, val json: String, val savedAt: Long)
@Dao interface ResourceDao {
    @Query("SELECT * FROM resource_cache WHERE accountId = :account AND path = :path") suspend fun get(account: String, path: String): CachedResource?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(resource: CachedResource)
    @Query("DELETE FROM resource_cache WHERE accountId = :account") suspend fun clear(account: String)
    @Query("DELETE FROM resource_cache WHERE accountId = :account AND ((:entity != '' AND (path = :entity OR substr(path, 1, length(:entity) + 1) = :entity || '/' OR substr(path, 1, length(:entity) + 1) = :entity || '?')) OR substr(path, 1, length(:collection) + 1) = :collection || '?')")
    suspend fun invalidate(account: String, entity: String, collection: String)
    @Query("DELETE FROM resource_cache WHERE savedAt < :cutoff") suspend fun prune(cutoff: Long)
    @Query("DELETE FROM resource_cache WHERE rowid NOT IN (SELECT rowid FROM resource_cache ORDER BY savedAt DESC LIMIT 300)") suspend fun bound()
}
@Database(entities = [CachedResource::class], version = 1, exportSchema = false)
abstract class MobDatabase : RoomDatabase() {
    abstract fun resources(): ResourceDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, MobDatabase::class.java, "mobgits-cache-v1.db").build()
    }
}
