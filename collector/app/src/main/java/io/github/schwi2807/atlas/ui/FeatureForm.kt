package io.github.schwi2807.atlas.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.schwi2807.atlas.SurveyController
import io.github.schwi2807.atlas.data.FeatureEntity
import io.github.schwi2807.atlas.suggest.SuggestionPack
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

val KINDS = listOf("building" to "Building", "entrance" to "Entrance", "poi" to "POI", "path_note" to "Path note")
val CATEGORIES = mapOf(
    "building" to listOf("faculty", "lecture_hall", "admin", "library", "hall", "sports", "residential", "clinic", "mosque", "building_other"),
    "entrance" to listOf("entrance"),
    "poi" to listOf("cafe", "surau", "atm", "printing", "bus_stop", "parking", "toilet", "kolej_block", "lecture_room", "lab",
        "shop", "office", "landmark", "ramp", "lift", "poi_other"),
    "path_note" to listOf("path_note"),
)
fun catLabel(c: String) = c.replace('_', ' ').replaceFirstChar { it.uppercase() }

private data class Room(val name: String, val code: String?, val floor: Int?)

private fun parseRooms(text: String): List<Room> = text.lines().mapNotNull { line ->
    val l = line.trim().ifEmpty { return@mapNotNull null }
    val floor = Regex("@\\s*(-?\\d+)\\s*$").find(l)?.groupValues?.get(1)?.toIntOrNull()
    val body = l.replace(Regex("@\\s*-?\\d+\\s*$"), "").trim()
    val m = Regex("^([A-Za-z]{0,3}\\d[\\w-]*)\\s+(.+)$").find(body)
    if (m != null) Room(m.groupValues[2], m.groupValues[1], floor) else Room(body, null, floor)
}

private fun roomsText(json: String): String = runCatching {
    Json.parseToJsonElement(json).jsonArray.joinToString("\n") { e ->
        val o = e as JsonObject
        listOfNotNull(o["code"]?.jsonPrimitive?.contentOrNullSafe(), o["name"]?.jsonPrimitive?.content).joinToString(" ") +
            (o["floor"]?.jsonPrimitive?.contentOrNullSafe()?.let { " @$it" } ?: "")
    }
}.getOrDefault("")

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this.toString() == "null") null else content

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeatureForm(survey: SurveyController, initial: FeatureEntity, isNew: Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val compass by survey.sensors.compass.collectAsState()
    val baro by survey.sensors.baro.collectAsState()

    var kind by remember { mutableStateOf(initial.kind) }
    var category by remember { mutableStateOf(initial.category) }
    var nameEn by remember { mutableStateOf(initial.nameEn) }
    var nameMs by remember { mutableStateOf(initial.nameMs ?: "") }
    var aliases by remember { mutableStateOf(runCatching { Json.parseToJsonElement(initial.aliasesJson).jsonArray.joinToString(", ") { it.jsonPrimitive.content } }.getOrDefault("")) }
    var code by remember { mutableStateOf(initial.code ?: "") }
    var parentId by remember { mutableStateOf(initial.parentId) }
    var floor by remember { mutableStateOf(initial.floor) }
    var floorSource by remember { mutableStateOf(initial.floorSource) }
    var floors by remember { mutableStateOf(initial.floors?.toString() ?: "") }
    var rooms by remember { mutableStateOf(roomsText(initial.roomsJson)) }
    var bearing by remember { mutableStateOf(initial.bearingDeg) }
    var bearingText by remember { mutableStateOf(initial.bearingDeg?.let { "%.0f".format(it) } ?: "") }
    var accessible by remember { mutableStateOf(initial.accessible) }
    var notes by remember { mutableStateOf(initial.notes ?: "") }
    var suggestion by remember { mutableStateOf(initial.suggestionJson) }
    val photos = remember { mutableStateListOf<JsonObject>().apply { addAll(Json.parseToJsonElement(initial.photosJson).jsonArray.map { it as JsonObject }) } }
    var buildings by remember { mutableStateOf(listOf<FeatureEntity>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        buildings = survey.db.features().buildings()
            .filter { it.id != initial.id }
            .sortedBy { SuggestionPack.distance(initial.lat, initial.lon, it.lat, it.lon) }
            .take(6)
    }
    val nearby = remember { survey.suggestions.nearby(initial.lat, initial.lon) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        if (ok && f != null && f.length() > 0) {
            photos += buildJsonObject {
                put("local", f.name)
                put("name", "${initial.id.take(8)}-${System.currentTimeMillis() % 1_000_000}.jpg")
                put("taken_at", System.currentTimeMillis())
            }
        } else f?.delete()
        pendingPhoto = null
    }
    fun addPhoto(f: File) {
        photos += buildJsonObject {
            put("local", f.name)
            put("name", "${initial.id.take(8)}-${System.currentTimeMillis() % 1_000_000}.jpg")
            put("taken_at", System.currentTimeMillis())
        }
    }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    cameraFile?.let { f ->
        CameraCapture(
            target = f,
            onDone = { ok -> if (ok && f.length() > 0) addPhoto(f) else f.delete(); cameraFile = null },
            onFailed = { // no CameraX camera: hand over to the system camera app
                cameraFile = null
                pendingPhoto = f
                takePhoto.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = gap) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (isNew) "NEW FEATURE" else "EDIT FEATURE", style = label.copy(fontSize = 12.sp, color = P.amber), modifier = Modifier.weight(1f))
            Text("±${fmt(initial.stdM, 1)} m · n=${initial.n}", style = label)
        }
        Panel("Kind") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = gap) {
                for ((k, l) in KINDS) Chip(l, kind == k) { kind = k; if (category !in CATEGORIES.getValue(k)) category = CATEGORIES.getValue(k).first() }
            }
        }
        if (CATEGORIES.getValue(kind).size > 1) Panel("Category") {
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = gap, verticalArrangement = gap) {
                for (c in CATEGORIES.getValue(kind)) Chip(catLabel(c), category == c) { category = c }
            }
        }
        Panel("Name (you confirm it: suggestions are only hints)") {
            if (nearby.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = gap) {
                    for (s in nearby) Chip(s.item.name, false, sub = "${s.item.source.uppercase()} · ${s.distanceM.toInt()} m") {
                        val (c, rest) = Regex("^([A-Z]{1,2}\\d{1,2}[A-Z]?)\\s+(.+)$").find(s.item.name)?.destructured?.let { (a, b) -> a to b } ?: (null to s.item.name)
                        nameEn = s.item.name_en ?: rest
                        if (!s.item.name_ms.isNullOrBlank()) nameMs = s.item.name_ms
                        if (c != null && code.isBlank()) code = c
                        suggestion = buildJsonObject {
                            put("source", s.item.source); put("text", s.item.name); put("distance_m", s.distanceM.toInt()); put("ref", s.item.ref)
                        }.toString()
                    }
                }
            } else Text("No suggestion pack loaded (Setup tab) or nothing named within 60 m.", fontSize = 11.sp, color = P.dim)
            Field("Name (English) *", nameEn) { nameEn = it }
            Field("Nama (Bahasa Melayu)", nameMs) { nameMs = it }
            Row(horizontalArrangement = gap) {
                Field("Code (G3, FKEE)", code, Modifier.weight(1f), caps = KeyboardCapitalization.Characters) { code = it }
                Field("Aliases, comma-separated", aliases, Modifier.weight(2f)) { aliases = it }
            }
        }
        if (kind == "entrance" || kind == "poi") Panel("In building") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = gap) {
                Chip("None / auto", parentId == null) { parentId = null }
                for (b in buildings) Chip(b.code ?: b.nameEn, parentId == b.id,
                    sub = "${SuggestionPack.distance(initial.lat, initial.lon, b.lat, b.lon).toInt()} m") { parentId = b.id }
            }
        }
        if (kind == "entrance") Panel("Door direction (facing IN, true north)") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap, modifier = Modifier.padding(top = 6.dp)) {
                if (compass.available) {
                    Text(compass.headingDeg?.let { "%03.0f°".format(it) } ?: "---°", style = big.copy(fontSize = 30.sp))
                    InstrButton("Lock", primary = true) {
                        compass.headingDeg?.let { bearing = it.toDouble(); bearingText = "%.0f".format(it) }
                    }
                }
                Field("Bearing °", bearingText, Modifier.width(110.dp), keyboard = KeyboardType.Number) {
                    bearingText = it; bearing = it.toDoubleOrNull()?.let { b -> ((b % 360) + 360) % 360 }
                }
            }
            // no compass (or a bad one): rough direction chips; the engine squares the arrow to the wall it snaps to
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = gap) {
                val parent = buildings.firstOrNull { it.id == parentId }
                if (parent != null) {
                    val toward = bearingTo(initial.lat, initial.lon, parent.lat, parent.lon)
                    Chip("Toward ${parent.code ?: "building"}", bearing != null && kotlin.math.abs(bearing!! - toward) < 0.5,
                        sub = "%03.0f°".format(toward)) { bearing = toward; bearingText = "%.0f".format(toward) }
                }
                for ((i, n) in listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").withIndex()) {
                    val b = i * 45.0
                    Chip(n, bearing == b) { bearing = b; bearingText = "%.0f".format(b) }
                }
            }
            Text(
                if (!compass.available) "No compass on this phone: pick the direction you face walking in. The build squares it to the wall."
                else if (compass.accuracy < 2) "Compass needs calibration: wave the phone in a figure 8."
                else "Stand in the doorway facing into the building, then Lock.",
                fontSize = 11.sp, color = if (compass.available && compass.accuracy < 2) P.bad else P.muted,
            )
        }
        if (kind == "building") Panel("Building") {
            Row(horizontalArrangement = gap) {
                Field("Storeys", floors, Modifier.width(110.dp), keyboard = KeyboardType.Number) { floors = it.filter(Char::isDigit).take(2) }
            }
            Field("Rooms, one per line: CODE Name @floor", rooms, singleLine = false) { rooms = it }
        }
        if (kind == "poi" || kind == "entrance") Panel("Floor") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap, modifier = Modifier.padding(top = 6.dp)) {
                InstrButton("−") { floor = (floor ?: 0) - 1; floorSource = "manual" }
                Text(floor?.let { if (it == 0) "G" else "$it" } ?: "—", style = big.copy(fontSize = 30.sp))
                InstrButton("+") { floor = (floor ?: 0) + 1; floorSource = "manual" }
                Spacer(Modifier.weight(1f))
                if (baro.available) {
                    if (baro.groundHPa == null) InstrButton("Calibrate G") { survey.sensors.calibrateGround() }
                    else InstrButton("Baro: ${baro.floorEstimate}") { floor = baro.floorEstimate; floorSource = "barometer" }
                }
            }
            Text(
                if (!baro.available) "No barometer on this phone: set the floor by hand."
                else if (baro.groundHPa == null) "Barometer: stand on the ground floor and tap Calibrate G once per session."
                else "Barometer: %.1f m above ground (%s source).".format(baro.relHeightM ?: 0f, floorSource ?: "no"),
                fontSize = 11.sp, color = P.muted,
            )
        }
        Panel("Access & notes") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Step-free (ramp / lift / level)", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = accessible == true, onCheckedChange = { accessible = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = P.bg, checkedTrackColor = P.amber))
            }
            Field("Notes", notes, singleLine = false) { notes = it }
        }
        Panel("Photos (no faces or number plates)") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = gap) {
                for (p in photos.toList()) {
                    val file = File(survey.photoDir, p["local"]!!.jsonPrimitive.content)
                    val bmp = remember(file) { decodeThumb(file) }
                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(84.dp).border(1.dp, P.line), contentScale = ContentScale.Crop)
                    InstrButton("✕") { photos.remove(p); file.delete() }
                }
                if (survey.sensors.availability.camera) InstrButton("+ Photo") {
                    cameraFile = File(survey.photoDir, "${initial.id.take(8)}-${System.currentTimeMillis()}.jpg")
                }
            }
        }
        error?.let { Banner(it, warn = true) }
        Row(horizontalArrangement = gap) {
            if (!isNew) InstrButton("Delete", Modifier.weight(1f)) { scope.launch { survey.deleteFeature(initial); onDone() } }
            if (!isNew) InstrButton("Re-capture", Modifier.weight(1f)) { survey.startCapture(initial.id); onDone() }
            InstrButton("Cancel", Modifier.weight(1f)) { if (isNew) photos.forEach { File(survey.photoDir, it["local"]!!.jsonPrimitive.content).delete() }; onDone() }
            InstrButton("Save", Modifier.weight(1.3f), primary = true) {
                error = when {
                    nameEn.isBlank() -> "Type or confirm the English name first."
                    kind == "entrance" && bearing == null -> "Entrances need the door direction: Lock the compass or type a bearing."
                    else -> null
                }
                if (error != null) return@InstrButton
                val roomsJson = buildJsonArray {
                    for (r in parseRooms(rooms)) add(buildJsonObject {
                        put("name", r.name); r.code?.let { put("code", it) }; put("floor", r.floor?.let(::JsonPrimitive) ?: JsonPrimitive(null as Int?)); put("kind", "other")
                    })
                }
                val aliasList = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val saved = initial.copy(
                    kind = kind, category = category, nameEn = nameEn.trim(), nameMs = nameMs.trim().ifEmpty { null },
                    aliasesJson = JsonArray(aliasList.map(::JsonPrimitive)).toString(), code = code.trim().ifEmpty { null },
                    parentId = if (kind == "entrance" || kind == "poi") parentId else null,
                    floor = if (kind == "poi" || kind == "entrance") floor else null,
                    floorSource = if (floor != null && (kind == "poi" || kind == "entrance")) floorSource ?: "manual" else null,
                    floors = if (kind == "building") floors.toIntOrNull()?.coerceIn(1, 30) else null,
                    roomsJson = if (kind == "building") roomsJson.toString() else "[]",
                    bearingDeg = if (kind == "entrance") bearing else null,
                    accessible = accessible, notes = notes.trim().ifEmpty { null },
                    photosJson = JsonArray(photos.toList()).toString(), suggestionJson = suggestion,
                )
                scope.launch { survey.saveFeature(saved); survey.clearDone(); onDone() }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(
    label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text, caps: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, onChange, modifier.padding(top = 4.dp), label = { Text(label, fontSize = 11.sp) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = caps),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = P.amber, unfocusedBorderColor = P.line, cursorColor = P.amber,
            focusedLabelColor = P.amber),
    )
}

private fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(dl) * kotlin.math.cos(p2)
    val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)
    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
}

private fun decodeThumb(f: File): android.graphics.Bitmap? = runCatching {
    val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
    android.graphics.BitmapFactory.decodeFile(f.path, o)
}.getOrNull()

@Suppress("unused")
private fun Uri.isEmpty() = this == Uri.EMPTY
