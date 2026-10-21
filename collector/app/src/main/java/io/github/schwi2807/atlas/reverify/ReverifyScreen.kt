package io.github.schwi2807.atlas.reverify

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.schwi2807.atlas.SurveyController
import io.github.schwi2807.atlas.suggest.SuggestionPack
import io.github.schwi2807.atlas.ui.Banner
import io.github.schwi2807.atlas.ui.Chip
import io.github.schwi2807.atlas.ui.InstrButton
import io.github.schwi2807.atlas.ui.P
import io.github.schwi2807.atlas.ui.catLabel
import io.github.schwi2807.atlas.ui.gap
import io.github.schwi2807.atlas.ui.label
import io.github.schwi2807.atlas.ui.value
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private enum class Scope(val label: String) { BUILDINGS("Buildings"), ALL("Everything"), UNCONFIRMED("Unconfirmed") }

/**
 * Re-verify: walks you to the places most in need of a visit. Order: unconfirmed buildings, then never walked
 * (OSM drafts), then the oldest last-verified date; ties broken by distance. "Verify here" captures a fresh
 * position with the form pre-filled from the published map; the engine merges it with the old record.
 */
@Composable
fun ReverifyScreen(survey: SurveyController, onVerify: (Place) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val campus by survey.campus.campus.collectAsState()
    val msg by survey.campus.message.collectAsState()
    val g by survey.gnss.state.collectAsState()
    val compass by survey.sensors.compass.collectAsState()
    var filter by remember { mutableStateOf(Scope.BUILDINGS) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { survey.campus.load() }

    val me = g.fix
    val rows = remember(campus, filter, me?.latitude?.let { (it * 2000).toInt() }, me?.longitude?.let { (it * 2000).toInt() }) {
        val c = campus ?: return@remember emptyList()
        c.places.asSequence()
            .filter {
                when (filter) {
                    Scope.BUILDINGS -> it.kind == "building"
                    Scope.ALL -> it.kind != "path_note"
                    Scope.UNCONFIRMED -> it.category == "building_unconfirmed"
                }
            }
            .map { p -> p to (me?.let { SuggestionPack.distance(it.latitude, it.longitude, p.lat, p.lon) }) }
            .sortedWith(compareBy<Pair<Place, Double?>>(
                { if (it.first.category == "building_unconfirmed") 0 else if (it.first.lastVerified == null) 1 else 2 },
                { it.first.lastVerified ?: "" },
                { it.second ?: Double.MAX_VALUE },
            ))
            .take(150).toList()
    }

    LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = gap) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RE-VERIFY", style = label.copy(fontSize = 12.sp, color = P.amber), modifier = Modifier.weight(1f))
                InstrButton("‹ Back") { onClose() }
            }
            Text(
                campus?.let { "Map build ${it.date} · ${it.places.size} places · ${it.places.count { p -> !p.verified }} not walked yet" } ?: "Loading the map…",
                style = label, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = gap) {
                for (s in Scope.entries) Chip(s.label, filter == s) { filter = s }
                InstrButton(if (busy) "Updating…" else "Update from site", enabled = !busy) {
                    busy = true; scope.launch { survey.campus.update(); busy = false }
                }
            }
            msg?.let { Banner(it, Modifier.padding(top = 8.dp)) }
            if (me == null) Banner("Waiting for a GPS fix: distances appear once you have one.", Modifier.padding(top = 8.dp), warn = true)
        }
        items(rows, key = { it.first.shortId }) { (p, d) ->
            val brg = me?.let { bearing(it.latitude, it.longitude, p.lat, p.lon) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(P.panel)
                    .border(1.dp, if (p.category == "building_unconfirmed") P.amber else P.line, RoundedCornerShape(6.dp))
                    .clickable { onVerify(p) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Arrow(brg?.let { it - (compass.headingDeg ?: 0f) }, northUp = compass.headingDeg == null)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    val showCode = p.code != null && !p.nameEn.contains(p.code)
                    Text((if (showCode) "${p.code}  " else "") + p.nameEn, fontSize = 15.sp, maxLines = 1)
                    Text(
                        when {
                            p.category == "building_unconfirmed" -> "Unconfirmed: does it exist? what is it?"
                            p.lastVerified == null -> catLabel(p.category) + " · never walked"
                            else -> catLabel(p.category) + " · checked ${p.lastVerified}"
                        },
                        style = label,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(d?.let { if (it < 1000) "${it.toInt()} m" else "%.1f km".format(it / 1000) } ?: "—", style = value.copy(color = P.amber))
                    Text(brg?.let { "%03.0f° ${cardinal(it)}".format(it) } ?: "", style = label)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Direction arrow: relative to where the phone points if it has a compass, else north-up with the true bearing. */
@Composable
private fun Arrow(deg: Float?, northUp: Boolean) {
    Canvas(Modifier.size(34.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        if (deg == null) { drawCircle(P.dim, size.minDimension / 6, c); return@Canvas }
        rotate(deg, c) {
            val w = size.width
            val path = Path().apply { moveTo(w / 2, 2f); lineTo(w - 4f, w - 4f); lineTo(w / 2, w * 0.72f); lineTo(4f, w - 4f); close() }
            drawPath(path, if (northUp) P.muted else P.amber)
        }
    }
}

fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
}

private fun cardinal(d: Float) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((d + 22.5f) % 360) / 45).toInt()]
