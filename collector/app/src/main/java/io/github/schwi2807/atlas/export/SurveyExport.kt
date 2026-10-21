package io.github.schwi2807.atlas.export

import io.github.schwi2807.atlas.data.FeatureEntity
import io.github.schwi2807.atlas.data.FixEntity
import io.github.schwi2807.atlas.data.SessionEntity
import io.github.schwi2807.atlas.data.TrackPointEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.round

/** What the manifest says about the phone. Model and SDK only: never a person's or account name. */
data class DeviceInfo(val manufacturer: String, val model: String, val sdk: Int, val gnssHardware: String?)
data class SensorInfo(val barometer: Boolean, val compass: Boolean, val camera: Boolean, val rawGnss: Boolean, val gnssStatus: Boolean = true)

/**
 * Writes one .uthmsurvey zip (format: schema/survey-manifest.schema.json + feature/track schemas):
 *   manifest.json, features.geojson, track.geojson, fixes.csv, photos/<file>
 * Pure JVM code (no Android types) so the unit tests validate real exports against the shared JSON Schemas.
 */
object SurveyExport {
    const val FORMAT = "uthmsurvey"
    val FIXES_HEADER = listOf("t_ms", "lat", "lon", "alt_m", "acc_m", "speed_mps", "bearing_deg", "provider",
        "sats_used", "sats_visible", "feature_id")
    private val json = Json { prettyPrint = false }
    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")

    // explicit pattern: OffsetDateTime.toString() drops ":00" seconds, which is not valid RFC 3339
    private val rfc3339 = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    fun iso(ms: Long): String = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).truncatedTo(ChronoUnit.SECONDS).format(rfc3339)
    private fun r(v: Double, places: Int): Double { val f = Math.pow(10.0, places.toDouble()); return round(v * f) / f }

    fun write(
        out: OutputStream,
        schemaVersion: Int,
        appVersion: String,
        campusId: String,
        session: SessionEntity,
        features: List<FeatureEntity>,
        track: List<TrackPointEntity>,
        fixes: List<FixEntity>,
        device: DeviceInfo,
        sensors: SensorInfo,
        settings: Map<String, Number>,
        exportedAt: Long,
        photo: (String) -> ByteArray?,
    ) {
        ZipOutputStream(out).use { z ->
            val photoFiles = LinkedHashMap<String, ByteArray>()
            val fc = buildJsonObject {
                put("type", "FeatureCollection")
                putJsonArray("features") {
                    for (f in features) add(feature(f, campusId, photo, photoFiles))
                }
            }
            val trackFc = trackGeoJson(track)
            val manifest = buildJsonObject {
                put("format", FORMAT)
                put("schema_version", schemaVersion)
                put("app_version", appVersion)
                putJsonObject("session") {
                    put("id", session.id)
                    put("campus_id", campusId)
                    put("title", session.title)
                    put("started_at", iso(session.startedAt))
                    put("ended_at", session.endedAt?.let { JsonPrimitive(iso(it)) } ?: JsonNull)
                    put("exported_at", iso(exportedAt))
                }
                putJsonObject("device") {
                    put("manufacturer", device.manufacturer.take(40))
                    put("model", device.model.take(60))
                    put("android_sdk", device.sdk)
                    put("gnss_hardware", device.gnssHardware?.take(120)?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                putJsonObject("sensors") {
                    put("barometer", sensors.barometer)
                    put("compass", sensors.compass)
                    put("camera", sensors.camera)
                    put("raw_gnss", sensors.rawGnss)
                    put("gnss_status", sensors.gnssStatus)
                }
                putJsonObject("settings") { settings.forEach { (k, v) -> put(k, v) } }
                putJsonObject("counts") {
                    put("features", features.size)
                    put("track_points", track.size)
                    put("fix_samples", fixes.size)
                    put("photos", photoFiles.size)
                }
                putJsonObject("files") {
                    put("features", "features.geojson")
                    put("track", "track.geojson")
                    put("fixes", "fixes.csv")
                    put("raw_gnss", JsonNull)
                    put("photos_dir", "photos/")
                }
            }
            z.entry("manifest.json", json.encodeToString(JsonElement.serializer(), manifest).toByteArray())
            z.entry("features.geojson", json.encodeToString(JsonElement.serializer(), fc).toByteArray())
            z.entry("track.geojson", json.encodeToString(JsonElement.serializer(), trackFc).toByteArray())
            z.entry("fixes.csv", fixesCsv(fixes).toByteArray())
            for ((name, bytes) in photoFiles) z.entry(name, bytes)
        }
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    fun feature(f: FeatureEntity, campusId: String, photo: (String) -> ByteArray?, photoFiles: MutableMap<String, ByteArray>): JsonObject =
        buildJsonObject {
            put("type", "Feature")
            put("id", f.id)
            putJsonObject("geometry") {
                put("type", "Point")
                putJsonArray("coordinates") {
                    add(r(f.lon, 7)); add(r(f.lat, 7))
                    f.alt?.let { add(r(it, 1)) }
                }
            }
            putJsonObject("properties") {
                put("campus_id", campusId)
                put("kind", f.kind)
                put("category", f.category)
                put("name_en", f.nameEn.trim())
                put("name_ms", f.nameMs?.trim()?.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
                put("aliases", Json.parseToJsonElement(f.aliasesJson).jsonArray.distinct().let(::JsonArray))
                put("code", f.code?.trim()?.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
                put("parent_id", f.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("floor", f.floor?.let { JsonPrimitive(it) } ?: JsonNull)
                put("floor_source", f.floorSource?.let { JsonPrimitive(it) } ?: JsonNull)
                put("floors", f.floors?.let { JsonPrimitive(it) } ?: JsonNull)
                put("rooms", Json.parseToJsonElement(f.roomsJson))
                if (f.kind == "entrance" || f.bearingDeg != null)
                    put("bearing_deg", f.bearingDeg?.let { JsonPrimitive(r(((it % 360) + 360) % 360, 1).let { b -> if (b >= 360.0) 0.0 else b }) } ?: JsonNull)
                put("accessible", f.accessible?.let { JsonPrimitive(it) } ?: JsonNull)
                putJsonObject("capture") {
                    put("method", f.captureMethod)
                    put("n", f.n)
                    put("std_m", r(f.stdM, 2))
                    put("mean_acc_m", r(f.meanAccM, 2))
                    put("best_acc_m", r(f.bestAccM, 2))
                    put("sats_used", f.satsUsed)
                    put("sats_visible", f.satsVisible)
                    put("stop_reason", f.stopReason?.lowercase(Locale.ROOT)?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("started_at", iso(f.captureStart))
                    put("ended_at", iso(f.captureEnd))
                    put("recaptures", f.recaptures)
                }
                putJsonArray("photos") {
                    for (p in Json.parseToJsonElement(f.photosJson).jsonArray) {
                        val o = p as JsonObject
                        val local = (o["local"] as JsonPrimitive).content
                        val bytes = photo(local) ?: continue
                        val name = "photos/" + (o["name"] as JsonPrimitive).content
                        photoFiles[name] = bytes
                        add(buildJsonObject {
                            put("file", name)
                            put("sha256", sha256(bytes))
                            (o["taken_at"] as? JsonPrimitive)?.let { put("taken_at", iso(it.content.toLong())) }
                            put("caption", o["caption"] ?: JsonNull)
                        })
                    }
                }
                put("notes", f.notes?.trim()?.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
                put("name_suggestion", f.suggestionJson?.let { Json.parseToJsonElement(it) } ?: JsonNull)
                put("source", "survey")
                put("verified", true)
                put("last_verified", f.lastVerified)
                put("created_at", iso(f.createdAt))
                put("updated_at", iso(f.updatedAt))
            }
        }

    fun trackGeoJson(track: List<TrackPointEntity>): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            for ((seg, pts) in track.groupBy { it.segment }.toSortedMap()) {
                if (pts.isEmpty()) continue
                add(buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        put("coordinates", buildJsonArray { pts.forEach { p -> add(buildJsonArray { add(r(p.lon, 7)); add(r(p.lat, 7)) }) } })
                    }
                    putJsonObject("properties") {
                        put("segment", seg)
                        put("t", buildJsonArray { pts.forEach { add(it.tMs) } })
                        put("acc", buildJsonArray { pts.forEach { add(r(it.acc.toDouble(), 1)) } })
                    }
                })
            }
        }
    }

    fun fixesCsv(fixes: List<FixEntity>): String = buildString {
        append(FIXES_HEADER.joinToString(",")).append('\n')
        for (f in fixes) {
            append(f.tMs).append(',').append(r(f.lat, 7)).append(',').append(r(f.lon, 7)).append(',')
            append(f.alt?.let { r(it, 1) } ?: "").append(',').append(r(f.acc.toDouble(), 2)).append(',')
            append(f.speed?.let { r(it.toDouble(), 2) } ?: "").append(',').append(f.bearing?.let { r(it.toDouble(), 1) } ?: "").append(',')
            append(f.provider).append(',').append(f.satsUsed).append(',').append(f.satsVisible).append(',')
            append(f.featureId ?: "").append('\n')
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
