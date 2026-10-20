package io.github.schwi2807.atlas.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One walk. Exported as one .uthmsurvey file. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val exportedAt: Long? = null,
)

/**
 * A captured feature. Field names follow schema/feature.schema.json (properties.*), flattened for Room.
 * Lists (aliases, rooms, photos) are stored as JSON text.
 */
@Entity(tableName = "features", indices = [Index("sessionId")])
data class FeatureEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: String,               // building | entrance | poi | path_note
    val category: String,
    val nameEn: String,
    val nameMs: String? = null,
    val aliasesJson: String = "[]",
    val code: String? = null,
    val parentId: String? = null,
    val floor: Int? = null,
    val floorSource: String? = null, // barometer | manual
    val floors: Int? = null,
    val roomsJson: String = "[]",
    val bearingDeg: Double? = null,
    val accessible: Boolean? = null,
    val notes: String? = null,
    val lon: Double,
    val lat: Double,
    val alt: Double? = null,
    // capture (averaged fix)
    val captureMethod: String = "averaged",
    val n: Int,
    val stdM: Double,
    val meanAccM: Double,
    val bestAccM: Double,
    val satsUsed: Int,
    val satsVisible: Int,
    val stopReason: String? = null,
    val captureStart: Long,
    val captureEnd: Long,
    val recaptures: Int = 0,
    val photosJson: String = "[]",
    val suggestionJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastVerified: String,       // yyyy-MM-dd
)

/** Every raw fix received while the app records (feeds fixes.csv, the coverage layer and the engine). */
@Entity(tableName = "fixes", indices = [Index("sessionId")])
data class FixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val acc: Float,
    val speed: Float?,
    val bearing: Float?,
    val provider: String,
    val satsUsed: Int,
    val satsVisible: Int,
    val featureId: String? = null,
)

/** Breadcrumb track (thinned fixes from the foreground service), split into segments when recording pauses. */
@Entity(tableName = "track", indices = [Index("sessionId")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val segment: Int,
)
