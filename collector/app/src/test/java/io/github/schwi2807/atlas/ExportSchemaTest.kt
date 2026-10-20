package io.github.schwi2807.atlas

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import io.github.schwi2807.atlas.data.FeatureEntity
import io.github.schwi2807.atlas.data.FixEntity
import io.github.schwi2807.atlas.data.SessionEntity
import io.github.schwi2807.atlas.data.TrackPointEntity
import io.github.schwi2807.atlas.export.DeviceInfo
import io.github.schwi2807.atlas.export.SensorInfo
import io.github.schwi2807.atlas.export.SurveyExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Exports a small session and validates every part against the shared schemas in /schema (single source of truth). */
class ExportSchemaTest {
    private val mapper = ObjectMapper()
    private val schemaDir = File(System.getProperty("atlas.schemaDir") ?: "../../schema")

    private fun schema(name: String): JsonSchema {
        val cfg = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(File(schemaDir, name).readText(), cfg)
    }

    private fun assertValid(s: JsonSchema, node: JsonNode, what: String) {
        val errors = s.validate(node)
        assertTrue("$what: $errors", errors.isEmpty())
    }

    private fun export(): Map<String, ByteArray> {
        val t0 = 1_792_500_000_000L
        val session = SessionEntity("0f7e3a1c-5b2d-4e8f-9a10-3c4d5e6f7a8b", "Walk test", t0, t0 + 3_600_000)
        fun f(id: String, kind: String, cat: String, name: String, extra: (FeatureEntity) -> FeatureEntity = { it }) = extra(FeatureEntity(
            id = id, sessionId = session.id, kind = kind, category = cat, nameEn = name, lon = 103.0855, lat = 1.8585, alt = 12.0,
            n = 30, stdM = 1.4, meanAccM = 4.0, bestAccM = 3.1, satsUsed = 21, satsVisible = 33, stopReason = "COUNT",
            captureStart = t0, captureEnd = t0 + 30_000, createdAt = t0, updatedAt = t0, lastVerified = "2026-10-21",
        ))
        val building = f("11111111-2222-4333-8444-555555555555", "building", "faculty", "Sample Faculty") {
            it.copy(code = "G3", aliasesJson = """["FKX","Blok G"]""", floors = 4,
                roomsJson = """[{"name":"Lecture Room 1","code":"BK1","floor":1,"kind":"other"}]""",
                photosJson = """[{"local":"p1.jpg","name":"11111111-1.jpg","taken_at":$t0}]""",
                suggestionJson = """{"source":"osm","text":"G3 Sample","distance_m":12,"ref":"osm:way/1"}""")
        }
        val entrance = f("66666666-7777-4888-9999-aaaaaaaaaaaa", "entrance", "entrance", "Main entrance") {
            it.copy(parentId = building.id, bearingDeg = 359.96, accessible = true, floor = 0, floorSource = "manual", stopReason = "MANUAL")
        }
        val note = f("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff", "path_note", "path_note", "Covered walkway") { it.copy(notes = "Shade") }
        val track = (0 until 6).map { TrackPointEntity(0, session.id, t0 + it * 5000, 1.8585 + it * 1e-5, 103.0855, 4f, if (it < 3) 0 else 1) }
        val fixes = (0 until 4).map { FixEntity(0, session.id, t0 + it * 1000, 1.8585, 103.0855, 12.0, 3.5f, 0.2f, null, "gps", 20, 30, null) }
        val out = ByteArrayOutputStream()
        SurveyExport.write(out, 1, "0.1.0", "parit-raja", session, listOf(building, entrance, note), track, fixes,
            DeviceInfo("Xiaomi", "Redmi 10C", 33, null), SensorInfo(barometer = false, compass = true, camera = true, rawGnss = false),
            mapOf("capture_samples" to 30), t0 + 4_000_000) { name -> if (name == "p1.jpg") byteArrayOf(1, 2, 3) else null }
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() }
        }
        return files
    }

    @Test
    fun exportMatchesSharedSchemas() {
        val files = export()
        assertEquals(setOf("manifest.json", "features.geojson", "track.geojson", "fixes.csv", "photos/11111111-1.jpg"), files.keys)
        assertValid(schema("survey-manifest.schema.json"), mapper.readTree(files["manifest.json"]), "manifest")
        val fc = mapper.readTree(files["features.geojson"])
        val featureSchema = schema("feature.schema.json")
        assertEquals(3, fc["features"].size())
        for (f in fc["features"]) assertValid(featureSchema, f, "feature ${f["properties"]["name_en"]}")
        assertValid(schema("track.schema.json"), mapper.readTree(files["track.geojson"]), "track")
    }

    @Test
    fun exportDetails() {
        val files = export()
        val fc = mapper.readTree(files["features.geojson"])["features"]
        val entrance = fc.first { it["properties"]["kind"].asText() == "entrance" }["properties"]
        assertEquals(0.0, entrance["bearing_deg"].asDouble(), 0.0)            // 359.96 rounds to 360 -> wraps to 0
        assertEquals("manual", entrance["capture"]["stop_reason"].asText())
        val building = fc.first { it["properties"]["kind"].asText() == "building" }["properties"]
        assertEquals(SurveyExport.sha256(byteArrayOf(1, 2, 3)), building["photos"][0]["sha256"].asText())
        val manifest = mapper.readTree(files["manifest.json"])
        assertEquals(1, manifest["counts"]["photos"].asInt())
        assertTrue(manifest["session"]["started_at"].asText().endsWith("+08:00"))
        val csv = files["fixes.csv"]!!.decodeToString().lines()
        assertEquals(SurveyExport.FIXES_HEADER.joinToString(","), csv[0])
        assertEquals(5, csv.count { it.isNotBlank() })
        // two track segments -> two LineStrings
        assertEquals(2, mapper.readTree(files["track.geojson"])["features"].size())
    }
}
