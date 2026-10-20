package io.github.schwi2807.atlas.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun all(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(s: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FeatureDao {
    @Query("SELECT * FROM features WHERE sessionId = :sid ORDER BY createdAt DESC")
    fun forSession(sid: String): Flow<List<FeatureEntity>>

    @Query("SELECT * FROM features WHERE sessionId = :sid ORDER BY createdAt")
    suspend fun listForSession(sid: String): List<FeatureEntity>

    @Query("SELECT * FROM features ORDER BY createdAt DESC")
    fun all(): Flow<List<FeatureEntity>>

    @Query("SELECT * FROM features WHERE kind = 'building' ORDER BY nameEn")
    suspend fun buildings(): List<FeatureEntity>

    @Query("SELECT * FROM features WHERE id = :id")
    suspend fun get(id: String): FeatureEntity?

    @Query("SELECT COUNT(*) FROM features WHERE sessionId = :sid")
    fun countForSession(sid: String): Flow<Int>

    @Upsert
    suspend fun upsert(f: FeatureEntity)

    @Delete
    suspend fun delete(f: FeatureEntity)

    @Query("DELETE FROM features WHERE sessionId = :sid")
    suspend fun deleteSession(sid: String)
}

@Dao
interface FixDao {
    @Insert
    suspend fun insert(f: FixEntity)

    @Query("SELECT * FROM fixes WHERE sessionId = :sid ORDER BY tMs")
    suspend fun forSession(sid: String): List<FixEntity>

    /** Coverage layer: every fix ever recorded (thinned in the query to keep the map light). */
    @Query("SELECT * FROM fixes WHERE id % :every = 0 ORDER BY tMs")
    suspend fun sample(every: Int): List<FixEntity>

    @Query("UPDATE fixes SET featureId = :featureId WHERE sessionId = :sid AND tMs BETWEEN :from AND :to")
    suspend fun tag(sid: String, from: Long, to: Long, featureId: String)

    @Query("DELETE FROM fixes WHERE sessionId = :sid")
    suspend fun deleteSession(sid: String)
}

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: TrackPointEntity)

    @Query("SELECT * FROM track WHERE sessionId = :sid ORDER BY tMs")
    suspend fun forSession(sid: String): List<TrackPointEntity>

    @Query("SELECT * FROM track ORDER BY sessionId, tMs")
    fun all(): Flow<List<TrackPointEntity>>

    @Query("SELECT COALESCE(MAX(segment), -1) FROM track WHERE sessionId = :sid")
    suspend fun lastSegment(sid: String): Int

    @Query("DELETE FROM track WHERE sessionId = :sid")
    suspend fun deleteSession(sid: String)
}

@Database(
    entities = [SessionEntity::class, FeatureEntity::class, FixEntity::class, TrackPointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AtlasDb : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun features(): FeatureDao
    abstract fun fixes(): FixDao
    abstract fun track(): TrackDao

    companion object {
        @Volatile private var instance: AtlasDb? = null

        fun get(context: Context): AtlasDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AtlasDb::class.java, "atlas.db")
                .build().also { instance = it }
        }
    }
}
